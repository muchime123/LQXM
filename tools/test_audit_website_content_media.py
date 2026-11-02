import unittest

from audit_website_content_media import references


class WebsiteContentMediaReferencesTest(unittest.TestCase):
    def test_json_and_html_internal_files_are_found_without_duplicate_external_entries(self):
        value = ('{"image":"/api/files/company?encoded=abc",'
                 '"body":"<img src=\\"https://example.com/api/file-assets/product_model/assets/7/preview\\">"}')

        found = references(value)

        self.assertEqual(2, sum(row["kind"] == "internal_archive" for row in found))
        self.assertEqual(0, sum(row["kind"] == "external_review" for row in found))

    def test_website_media_and_static_images_are_not_archive_dependencies(self):
        found = references('/api/website/media/12/content /images/factory.webp')
        self.assertEqual({"website_media", "public_static"}, {row["kind"] for row in found})


if __name__ == "__main__":
    unittest.main()
