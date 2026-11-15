"""Guarded, insert-only demo seed. Only teasmart_product_test is supported."""
import argparse, json, os, subprocess, sys
from pathlib import Path
DB = "teasmart_product_test"
HERE = Path(__file__).resolve().parent
DATA = json.loads((HERE / "catalog.json").read_text(encoding="utf-8"))

def literal(value):
    return "NULL" if value is None else "CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4) COLLATE utf8mb4_unicode_ci"

def query(sql):
    executable = Path(os.environ.get("MYSQL_CLIENT", "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"))
    env = os.environ.copy()
    if not env.get("DB_USERNAME") or not env.get("DB_PASSWORD"):
        raise RuntimeError("DB_USERNAME/DB_PASSWORD must exist in the current Process environment.")
    env["MYSQL_PWD"] = env["DB_PASSWORD"]
    cmd = [str(executable), "--host=127.0.0.1", "--port=3306", "--database="+DB,
           "--user="+env["DB_USERNAME"], "--default-character-set=utf8mb4", "--batch", "--skip-column-names", "--raw", "--connect-timeout=5"]
    result = subprocess.run(cmd, input=sql, capture_output=True, text=True, encoding="utf-8", env=env)
    if result.returncode:
        # Do not print connection parameters, credentials, SQL, or raw database errors.
        raise RuntimeError("MySQL statement failed; no --force used. An open DML transaction is rolled back on disconnect. Inspect metadata before retry.")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]

def rows(table, where="1=1"):
    if table not in {"categories", "tea_regions", "stores", "products"}:
        raise RuntimeError("Unexpected table")
    names = query("SELECT JSON_OBJECT('column',COLUMN_NAME) FROM information_schema.COLUMNS WHERE CONVERT(TABLE_SCHEMA USING utf8mb4) COLLATE utf8mb4_unicode_ci="+literal(DB)+" AND CONVERT(TABLE_NAME USING utf8mb4) COLLATE utf8mb4_unicode_ci="+literal(table)+" ORDER BY ORDINAL_POSITION")
    fields = ",".join(literal(r["column"])+",`"+r["column"]+"`" for r in names)
    return query("SELECT JSON_OBJECT("+fields+") FROM `"+DB+"`.`"+table+"` WHERE "+where)

def get_named(table, name):
    found = rows(table, "name="+literal(name))
    if len(found)>1:
        raise RuntimeError("Ambiguous existing record: "+table+" / "+name)
    return found[0] if found else None

def counts():
    return {r["table"]:r["count"] for r in query("SELECT JSON_OBJECT('table','categories','count',COUNT(*)) FROM categories UNION ALL SELECT JSON_OBJECT('table','tea_regions','count',COUNT(*)) FROM tea_regions UNION ALL SELECT JSON_OBJECT('table','products','count',COUNT(*)) FROM products UNION ALL SELECT JSON_OBJECT('table','stores','count',COUNT(*)) FROM stores")}

def product_preflight():
    store = DATA["store"]
    if not store["approved"] or not store["approvedExistingName"]:
        raise RuntimeError("PRODUCTS BLOCKED: Store has not been approved. No writes performed.")
    chosen = get_named("stores",store["approvedExistingName"])
    if not chosen or chosen["status"]!="ACTIVE":
        raise RuntimeError("Approved ACTIVE Store must already exist; this script never creates Store.")
    for item in DATA["products"]:
        if not item["regionMappingApproved"]:
            raise RuntimeError("PRODUCTS BLOCKED: Region mapping approval missing. No writes performed.")
        if not isinstance(item["priceVnd"],int) or not 0<item["priceVnd"]<10000000000:
            raise RuntimeError("Invalid demo price")
        if not isinstance(item["weightGrams"],int) or item["weightGrams"]<=0:
            raise RuntimeError("Invalid demo weight")
        if not 0<=item["stockQuantity"]<=4294967295:
            raise RuntimeError("Invalid stock")
        asset=(HERE.parents[3]/"public"/item["imageUrl"].lstrip("/")).resolve()
        if not asset.is_relative_to((HERE.parents[3]/"public").resolve()) or not asset.is_file():
            raise RuntimeError("Product image asset missing or unsafe")
        for table,key in [("categories","categoryName"),("tea_regions","proposedRegionName")]:
            record=get_named(table,item[key])
            if not record or record["status"]!="ACTIVE":raise RuntimeError("Missing approved ACTIVE "+table)
    return chosen

def insert(table, data, numeric=()):
    fields=",".join("`"+k+"`" for k in data)
    values=",".join(str(v) if k in numeric else literal(v) for k,v in data.items())
    return "INSERT INTO `"+DB+"`.`"+table+"` ("+fields+",created_at,updated_at) VALUES ("+values+",NOW(),NOW());"

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope",choices=["catalog","store","products"],default="catalog")
    parser.add_argument("--apply",action="store_true")
    parser.add_argument("--report",type=Path,required=True)
    args=parser.parse_args()
    meta=query("SELECT JSON_OBJECT('db',DATABASE(),'version',VERSION())")[0]
    if meta["db"]!=DB or DATA["onlyDatabase"]!=DB:raise RuntimeError("Wrong database")
    before=counts()
    original={t:rows(t) for t in ["categories","tea_regions","stores","products"]}
    created=[];skipped=[];statements=[]
    if args.scope=="catalog":
        groups=[("categories",DATA["categories"]),("tea_regions",DATA["regions"])]
        for table,items in groups:
            for item in items:
                existing=get_named(table,item["name"])
                if existing:
                    skipped.append({"table":table,"name":item["name"],"id":existing["category_id" if table=="categories" else "region_id"],"existingStatus":existing["status"]});continue
                if table=="categories" and rows(table,"slug="+literal(item["slug"])):
                    raise RuntimeError("Slug collision; refusing to overwrite.")
                statements.append(insert(table,item));created.append({"table":table,"name":item["name"]})
    elif args.scope=="store":
        approved=DATA["store"]
        if not approved.get("creationApproved") or not approved["approved"]:
            raise RuntimeError("Store creation approval missing")
        name=approved["approvedExistingName"]
        existing=get_named("stores",name)
        if existing:skipped.append({"table":"stores","name":name,"id":existing["store_id"]})
        else:
            values={"name":name,"description":"Store thử nghiệm phục vụ đồ án TeaSmart; không phải thông tin cửa hàng kinh doanh đã xác minh.","status":"ACTIVE"}
            statements.append(insert("stores",values));created.append({"table":"stores","name":name})
    else:
        store=product_preflight()
        for item in DATA["products"]:
            existing=get_named("products",item["name"])
            if existing:
                skipped.append({"table":"products","name":item["name"],"id":existing["product_id"],"note":"Existing record preserved, no overwrite"});continue
            if rows("products","slug="+literal(item["slug"])):raise RuntimeError("Product slug collision")
            values={"category_id":get_named("categories",item["categoryName"])["category_id"],"region_id":get_named("tea_regions",item["proposedRegionName"])["region_id"],"store_id":store["store_id"],"name":item["name"],"slug":item["slug"],"description":item["description"],"price":item["priceVnd"],"stock_quantity":item["stockQuantity"],"weight_grams":item["weightGrams"],"image_url":item["imageUrl"],"status":"ACTIVE"}
            statements.append(insert("products",values,{"category_id","region_id","store_id","price","stock_quantity","weight_grams"}));created.append({"table":"products","name":item["name"],"demoSku":item["demoSku"]})
    if args.apply and statements:
        query("START TRANSACTION;\n"+"\n".join(statements)+"\nCOMMIT;")
    after=counts()
    for table,oldrows in original.items():
        idkey={"categories":"category_id","tea_regions":"region_id","stores":"store_id","products":"product_id"}[table]
        current={row[idkey]:row for row in rows(table)}
        if any(current.get(row[idkey])!=row for row in oldrows):raise RuntimeError("Existing catalog data changed; investigate, do not rerun automatically.")
    report={"database":DB,"mode":"APPLY" if args.apply else "DRY_RUN","scope":args.scope,"before":before,"after":after,"created" if args.apply else "planned":created,"skipped":skipped,"existingRowsPreserved":True,"storeAutoCreated":False,"schemaChanged":False,"productsApprovalPending":not DATA["store"]["approved"] or any(not p["regionMappingApproved"] for p in DATA["products"])}
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"database":DB,"before":before,"after":after,"createdOrPlanned":len(created),"skipped":len(skipped),"mode":report["mode"]}))

if __name__=="__main__":
    try:main()
    except (RuntimeError,OSError,ValueError) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
