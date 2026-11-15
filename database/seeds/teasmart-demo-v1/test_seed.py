import importlib.util, json, os, unittest
from pathlib import Path
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("demo_seed",HERE/"seed.py");seed=importlib.util.module_from_spec(spec);spec.loader.exec_module(seed)
class DemoSeedTests(unittest.TestCase):
    def test_exact_counts_and_unique_identity(self):
        d=seed.DATA;self.assertEqual(7,len(d["categories"]));self.assertEqual(5,len(d["regions"]));self.assertEqual(25,len(d["products"]));
        for field in ["name","slug","demoSku","imageUrl"]:self.assertEqual(25,len({p[field] for p in d["products"]}))
    def test_approved_prices_and_weights_preserved(self):
        self.assertEqual([450000,550000,780000,180000,390000,220000,95000,210000,520000,75000,170000,280000,145000,260000,130000,420000,250000,950000,490000,890000,620000,350000,165000,1150000,1290000],[p["priceVnd"] for p in seed.DATA["products"]])
        self.assertEqual([100,100,200,100,200,100,100,200,500,100,200,200,100,200,100,200,100,200,200,500,100,200,200,300,500],[p["weightGrams"] for p in seed.DATA["products"]])
    def test_unapproved_store_stops_before_query(self):
        original=seed.query;approved=seed.DATA["store"]["approved"];seed.DATA["store"]["approved"]=False
        seed.query=lambda sql: self.fail("Store approval gate must run before DB access")
        try:
            with self.assertRaisesRegex(RuntimeError,"Store has not been approved"):seed.product_preflight()
        finally:seed.query=original;seed.DATA["store"]["approved"]=approved
    def test_regions_and_categories_reference_known_names(self):
        cats={p["name"] for p in seed.DATA["categories"]};regions={p["name"] for p in seed.DATA["regions"]}
        for p in seed.DATA["products"]:self.assertIn(p["categoryName"],cats);self.assertIn(p["proposedRegionName"],regions);self.assertLessEqual(len(p["name"]),200);self.assertLessEqual(len(p["slug"]),250);self.assertLess(len(p["description"].encode("utf-8")),65536)
    def test_only_test_database_and_no_destructive_sql(self):
        self.assertEqual("teasmart_product_test",seed.DB)
        statement=seed.insert("categories",{"name":"Trà thử ' ; DROP TABLE products; --","slug":"test-slug","status":"ACTIVE"})
        self.assertTrue(statement.startswith("INSERT INTO `teasmart_product_test`.`categories`"));self.assertNotIn("DROP TABLE",statement)
    def test_original_placeholders_exist_are_distinct(self):
        root=Path(os.environ.get("TEASMART_FRONTEND_ROOT", str(HERE.parents[3])))
        assets=[root/"public"/p["imageUrl"].lstrip("/") for p in seed.DATA["products"]]
        self.assertTrue(all(p.exists() for p in assets));self.assertEqual(25,len({p.read_bytes() for p in assets}))
if __name__=="__main__":unittest.main()
