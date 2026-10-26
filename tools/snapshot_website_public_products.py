"""Save a read-only snapshot of the current public product API for migration review.

Example: python tools/snapshot_website_public_products.py --base-url http://127.0.0.1:8081 --output-dir <isolated-directory>
Only GET requests are issued. The output may contain public marketing content and URLs.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import quote, urlparse
from urllib.request import urlopen


def fetch(base_url: str, path: str) -> tuple[bytes, object]:
    with urlopen(base_url + path, timeout=15) as response:
        if response.status != 200:
            raise ValueError(f"{path}: HTTP {response.status}")
        body = response.read()
    return body, json.loads(body)


def snapshot(base_url: str, output_dir: Path) -> dict:
    pages = {
        "products.json": "/api/website/products?page=0&size=500",
        "featured.json": "/api/website/products/featured?limit=500",
        "categories.json": "/api/website/product-categories",
    }
    responses = {name: fetch(base_url, path) for name, path in pages.items()}
    product_page = responses["products.json"][1]
    products = product_page.get("items")
    if not isinstance(products, list) or product_page.get("total") != len(products):
        raise ValueError("product list is incomplete; resolve pagination before taking a snapshot")
    slugs = [item.get("websiteSlug") for item in products]
    if any(not isinstance(slug, str) or not slug for slug in slugs) or len(set(slugs)) != len(slugs):
        raise ValueError("published products have missing or duplicate slugs")
    details = {}
    for slug in slugs:
        body, data = fetch(base_url, "/api/website/products/" + quote(slug, safe=""))
        if data.get("model", {}).get("websiteSlug") != slug:
            raise ValueError(f"detail slug mismatch: {slug}")
        details[slug] = (body, data)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {"base_url": base_url, "product_count": len(products), "featured_count": len(responses["featured.json"][1]), "files": {}}
    for name, (body, _) in responses.items():
        (output_dir / name).write_bytes(body)
        manifest["files"][name] = hashlib.sha256(body).hexdigest()
    detail_dir = output_dir / "details"
    detail_dir.mkdir(exist_ok=True)
    for slug, (body, _) in details.items():
        # Stable filenames independent of Unicode, reserved names, and path separators.
        name = hashlib.sha256(slug.encode("utf-8")).hexdigest() + ".json"
        (detail_dir / name).write_bytes(body)
        manifest["files"]["details/" + name] = hashlib.sha256(body).hexdigest()
    manifest["products"] = [{"legacy_id": item["id"], "slug": item["websiteSlug"], "name": item["name"], "detail_file": "details/" + hashlib.sha256(item["websiteSlug"].encode("utf-8")).hexdigest() + ".json", "gallery_count": len(details[item["websiteSlug"]][1].get("gallery", [])), "variant_count": len(details[item["websiteSlug"]][1].get("variants", []))} for item in products]
    (output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    parsed = urlparse(args.base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path or parsed.query:
        parser.error("--base-url must be an HTTP(S) origin without path or query")
    manifest = snapshot(args.base_url.rstrip("/"), args.output_dir)
    print(json.dumps({"product_count": manifest["product_count"], "featured_count": manifest["featured_count"], "file_count": len(manifest["files"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
