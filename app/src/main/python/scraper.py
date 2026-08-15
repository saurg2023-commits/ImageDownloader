"""
Page image scraper, embedded in the Android app via Chaquopy.

Given a page URL, fetches the HTML and returns a de-duplicated list of
absolute image URLs found on the page: <img src>, <img data-src>
(lazy-loaded images), <img srcset>, CSS background-image declarations,
and og:image / twitter:image meta tags.

Hardened for a mobile client:
- Refuses non-http(s) schemes (blocks file://, javascript:, ftp:, etc.)
- Streams the response and aborts once MAX_DOWNLOAD_BYTES is exceeded,
  so a huge or non-HTML response can't exhaust device memory.
- Verifies the Content-Type is actually HTML before handing bytes to
  the parser.
- Caps the number of returned URLs, since Android's Binder IPC used to
  pass the result back to Kotlin has a ~1MB transaction limit; a page
  with thousands of images could otherwise crash the app.
- Every failure path returns [] rather than raising, matching the
  contract the Kotlin call site expects (it treats an exception from
  callAttr as "something went wrong" and any empty list as "no images
  found" -- those are different, so we log the distinction even though
  the return type can't carry it across the language boundary).

Requires: requests, beautifulsoup4 (declared in app/build.gradle).
"""

from __future__ import annotations

import logging
import re
from typing import Final
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("scraper")

REQUEST_TIMEOUT: Final[tuple[float, float]] = (5.0, 10.0)  # (connect, read) seconds
MAX_DOWNLOAD_BYTES: Final[int] = 8 * 1024 * 1024  # 8 MB cap on the page itself
MAX_RESULTS: Final[int] = 500  # cap on how many image URLs we hand back
ALLOWED_SCHEMES: Final[frozenset[str]] = frozenset({"http", "https"})
USER_AGENT: Final[str] = (
    "Mozilla/5.0 (Linux; Android 14) ImageDownloader/2.0 "
    "(+https://github.com/dev-divyansh/ImageDownloader)"
)
_BG_IMAGE_RE: Final[re.Pattern[str]] = re.compile(r"url\(\s*['\"]?([^'\")]+)['\"]?\s*\)")
_HTML_CONTENT_TYPES: Final[tuple[str, ...]] = ("text/html", "application/xhtml+xml")


def _session() -> requests.Session:
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "text/html,*/*"})
    return session


def _looks_like_image(url: str) -> bool:
    """Filter out obvious non-images (data URIs, empty/blank strings)."""
    return bool(url) and not url.startswith("data:")


def _resolve(base_url: str, candidate: str | None) -> str | None:
    if not candidate:
        return None
    candidate = candidate.strip()
    if not candidate or not _looks_like_image(candidate):
        return None
    resolved = urljoin(base_url, candidate)
    if urlparse(resolved).scheme not in ALLOWED_SCHEMES:
        return None
    return resolved


def _extract_srcset(base_url: str, srcset: str) -> list[str]:
    """srcset is a comma-separated list of 'url descriptor' pairs."""
    urls: list[str] = []
    for part in srcset.split(","):
        candidate = part.strip().split(" ")[0]
        resolved = _resolve(base_url, candidate)
        if resolved:
            urls.append(resolved)
    return urls


def _extract_images(base_url: str, soup: BeautifulSoup) -> list[str]:
    found: list[str] = []
    seen: set[str] = set()

    def add(url: str | None) -> bool:
        """Returns True once MAX_RESULTS is reached, so callers can stop early."""
        if url and url not in seen:
            seen.add(url)
            found.append(url)
        return len(found) >= MAX_RESULTS

    for img in soup.find_all("img"):
        for attr in ("src", "data-src", "data-original", "data-lazy-src"):
            if add(_resolve(base_url, img.get(attr))):
                return found
        srcset = img.get("srcset") or img.get("data-srcset")
        if srcset:
            for url in _extract_srcset(base_url, srcset):
                if add(url):
                    return found

    for tag in soup.find_all(style=True):
        match = _BG_IMAGE_RE.search(tag["style"])
        if match and add(_resolve(base_url, match.group(1))):
            return found

    for meta in soup.find_all("meta", attrs={"property": ["og:image", "og:image:url"]}):
        if add(_resolve(base_url, meta.get("content"))):
            return found
    for meta in soup.find_all("meta", attrs={"name": "twitter:image"}):
        if add(_resolve(base_url, meta.get("content"))):
            return found

    return found


def _fetch_html(session: requests.Session, url: str) -> tuple[str, str] | None:
    """Streams the response, enforcing a byte cap and an HTML content-type
    check before returning (decoded_text, final_url). Returns None on any
    failure or if the response isn't HTML."""
    try:
        response = session.get(
            url, timeout=REQUEST_TIMEOUT, allow_redirects=True, stream=True
        )
    except requests.exceptions.Timeout:
        logger.error("Timed out fetching %s", url)
        return None
    except requests.exceptions.ConnectionError as exc:
        logger.error("Connection failed for %s: %s", url, exc)
        return None
    except requests.exceptions.TooManyRedirects:
        logger.error("Too many redirects for %s", url)
        return None
    except requests.exceptions.RequestException as exc:
        logger.error("Request failed for %s: %s", url, exc)
        return None

    with response:
        try:
            response.raise_for_status()
        except requests.exceptions.HTTPError as exc:
            logger.error("HTTP error for %s: %s", url, exc)
            return None

        content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
        if content_type and content_type not in _HTML_CONTENT_TYPES:
            logger.info("Skipping non-HTML content-type %r at %s", content_type, url)
            return None

        chunks: list[bytes] = []
        total = 0
        for chunk in response.iter_content(chunk_size=64 * 1024):
            total += len(chunk)
            if total > MAX_DOWNLOAD_BYTES:
                logger.warning(
                    "Aborting fetch of %s: exceeded %d byte cap", url, MAX_DOWNLOAD_BYTES
                )
                return None
            chunks.append(chunk)

        raw = b"".join(chunks)
        if not raw:
            logger.info("Empty response body from %s", url)
            return None

        return raw.decode(response.encoding or "utf-8", errors="replace"), response.url


def main(url: str) -> list[str]:
    """Fetch `url` and return every image URL found on the page (capped at
    MAX_RESULTS). Returns an empty list -- never raises -- on network
    errors, non-HTML responses, oversized pages, or malformed input."""
    if not url or not url.strip():
        logger.warning("main() called with an empty URL")
        return []

    url = url.strip()
    if not urlparse(url).scheme:
        url = "https://" + url
    if urlparse(url).scheme not in ALLOWED_SCHEMES:
        logger.warning("Rejecting unsupported scheme in %r", url)
        return []

    fetched = _fetch_html(_session(), url)
    if fetched is None:
        return []
    html, final_url = fetched

    try:
        soup = BeautifulSoup(html, "html.parser")
    except Exception as exc:  # malformed markup shouldn't crash the app
        logger.error("Failed to parse %s: %s", final_url, exc)
        return []

    images = _extract_images(final_url, soup)
    if len(images) >= MAX_RESULTS:
        logger.info("Result capped at %d images for %s", MAX_RESULTS, final_url)
    else:
        logger.info("Found %d image(s) on %s", len(images), final_url)
    return images
