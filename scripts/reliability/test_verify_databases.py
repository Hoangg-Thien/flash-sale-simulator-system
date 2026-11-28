"""Các đối chứng âm dùng bản chụp nhỏ để kiểm tra chính công cụ đối soát."""

import copy
import unittest

from verify_databases import verify


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"opening_stock": {"1": 4}, "historical_ids": [10]}
        self.data = {
            "orders": [
                {
                    "id": i,
                    "idempotency_key": str(i),
                    "product_id": 1,
                    "item_product": 1,
                    "quantity": 2,
                    "order_status": "CONFIRMED",
                    "cancel_requested": False,
                }
                for i in (10, 11)
            ],
            "reservations": [{"order_id": 11, "product_id": 1, "quantity": 2, "status": "RESERVED"}],
            "inventory": [{"product_id": 1, "stock": 2}],
        }

    def test_historical_order_consumption_already_in_opening_balance(self):
        self.assertEqual(verify(self.data, self.manifest)["issues"], [])

    def test_inconsistent_snapshots_fail(self):
        for table, field, value in [
            ("inventory", "stock", 0),
            ("inventory", "stock", -1),
            ("reservations", "status", "RELEASED"),
            ("reservations", "quantity", 1),
            ("orders", "order_status", "REJECTED"),
            ("orders", "order_status", "TYPO"),
            ("orders", "idempotency_key", "10"),
            ("orders", "cancel_requested", True),
            ("reservations", "order_id", 10),
            ("reservations", "order_id", 12),
        ]:
            with self.subTest(table=table, field=field, value=value):
                changed = copy.deepcopy(self.data)
                changed[table][-1][field] = value
                self.assertTrue(verify(changed, self.manifest)["issues"])

    def test_pending_with_active_reservation_is_visible(self):
        self.data["orders"][-1]["order_status"] = "PENDING"
        result = verify(self.data, self.manifest)
        self.assertEqual(result["pending_ids"], [11])
        self.assertEqual(result["issues"], [])


if __name__ == "__main__":
    unittest.main()
