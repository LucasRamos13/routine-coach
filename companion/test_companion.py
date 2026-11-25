import unittest

from routine_coach_companion import parse


class ParseTest(unittest.TestCase):
    def test_reminder(self):
        msg = {"topic": "t", "message": "Training session at 18:00.", "tags": ["rc-42"]}
        self.assertEqual(parse(msg, "t-ack"), ("reminder", "42", "Training session at 18:00."))

    def test_ack(self):
        self.assertEqual(parse({"topic": "t-ack", "message": "ack:42:done:phone"}, "t-ack"), ("ack", "42", "done", "phone"))
        self.assertEqual(parse({"topic": "t-ack", "message": "ack:7:snooze:telegram"}, "t-ack"), ("ack", "7", "snooze", "telegram"))

    def test_ignores_foreign_messages(self):
        self.assertIsNone(parse({"topic": "t", "message": "hello", "tags": []}, "t-ack"))
        self.assertIsNone(parse({"topic": "t-ack", "message": "rm -rf"}, "t-ack"))


if __name__ == "__main__":
    unittest.main()
