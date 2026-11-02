import unittest

from cutover_website_content_media import replace_fields


class WebsiteContentMediaCutoverTest(unittest.TestCase):
    def test_replaces_only_audited_urls_and_preserves_other_json(self):
        row = {"image_url": "/api/files/company?encoded=one",
               "content": '{"title":"工厂","images":["/api/files/company?encoded=two"]}'}
        changes = [
            {"field": "image_url", "old_url": "/api/files/company?encoded=one",
             "new_url": "/api/website/media/10/content"},
            {"field": "content", "old_url": "/api/files/company?encoded=two",
             "new_url": "/api/website/media/11/content"},
        ]

        image, content = replace_fields(row, changes)

        self.assertEqual("/api/website/media/10/content", image)
        self.assertEqual({"title": "工厂", "images": ["/api/website/media/11/content"]},
                         __import__("json").loads(content))

    def test_refuses_ambiguous_or_changed_url(self):
        row = {"image_url": None, "content": "old old"}
        with self.assertRaises(ValueError):
            replace_fields(row, [{"field": "content", "old_url": "old", "new_url": "new"}])


if __name__ == "__main__":
    unittest.main()
