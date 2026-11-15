"""Approved 2026-10-26 main append; INSERT only. Original test seeder unchanged.
Requires a verified backup. No credentials or user rows are written to reports.
"""
import argparse, hashlib, json, sys
from pathlib import Path
import seed

DB = 'teasmart'
seed.DB = DB  # Explicit main-only adapter; the original CLI remains test-only.
ROOT = Path(__file__).resolve().parents[4]
IDS = {'categories':'category_id','tea_regions':'region_id','stores':'store_id','products':'product_id'}

def snapshot():
    out = {}
    tables = seed.query("SELECT JSON_OBJECT('name',TABLE_NAME) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME")
    for table in tables:
        t = table['name']
        cols = seed.query("SELECT JSON_OBJECT('name',COLUMN_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND CONVERT(TABLE_NAME USING utf8mb4) COLLATE utf8mb4_unicode_ci="+seed.literal(t)+" ORDER BY ORDINAL_POSITION")
        out[t] = seed.query('SELECT JSON_OBJECT('+','.join(seed.literal(c['name'])+',`'+c['name']+'`' for c in cols)+') FROM `'+t+'`')
    return out

def encoded(row):
    return json.dumps(row,sort_keys=True,ensure_ascii=False,separators=(',',':'))

def digest(rows):
    return hashlib.sha256('\n'.join(sorted(encoded(r) for r in rows)).encode()).hexdigest()

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--backup-verification',type=Path,required=True)
    ap.add_argument('--report',type=Path,required=True)
    ap.add_argument('--apply',action='store_true')
    ap.add_argument('--ack-main',action='store_true')
    args=ap.parse_args()
    backup=json.loads(args.backup_verification.read_text(encoding='utf-8'))
    assert backup['restoreStatus']=='PASS', 'Verified restore required'
    assert hashlib.sha256(Path(backup['backup']).read_bytes()).hexdigest()==backup['sha256'], 'Backup checksum mismatch'
    assert seed.query("SELECT JSON_OBJECT('db',DATABASE())")[0]['db']==DB
    assert seed.DATA['store']['approved'] and seed.DATA['store']['creationApproved']
    provenance=json.loads((seed.HERE/'image-provenance.json').read_text(encoding='utf-8'))
    for asset in provenance['assets']:
        p=ROOT/'public/demo/tea'/asset['target']
        assert hashlib.sha256(p.read_bytes()).hexdigest()==asset['sha256'], 'Asset checksum mismatch'
    before=snapshot(); assert len(before)==19
    statements=[];created=[];skipped=[]
    def add(table, values, numeric=()):
        existing=seed.get_named(table,values['name'])
        if existing:
            assert all(existing.get(k)==v for k,v in values.items()), 'Existing name has conflicting fields: '+table+' / '+values['name']
            skipped.append({'table':table,'name':values['name']});return
        if 'slug' in values:
            assert not seed.rows(table,'slug='+seed.literal(values['slug'])), 'Slug collision'
        statements.append(seed.insert(table,values,numeric));created.append({'table':table,'name':values['name']})
    for item in seed.DATA['categories']:add('categories',item)
    for item in seed.DATA['regions']:add('tea_regions',item)
    store=seed.DATA['store']['approvedExistingName']
    add('stores',{'name':store,'description':'Store thử nghiệm phục vụ đồ án TeaSmart; không phải thông tin cửa hàng kinh doanh đã xác minh.','status':'ACTIVE'})
    for item in seed.DATA['products']:
        assert item['regionMappingApproved'] and item['priceVnd']>0 and item['weightGrams']>0
        assert not seed.rows('products','description LIKE '+seed.literal('%'+item['demoSku']+'%')+' AND name<>'+seed.literal(item['name'])), 'Demo SKU collision'
        values={'name':item['name'],'slug':item['slug'],'description':item['description'],'price':item['priceVnd'],'stock_quantity':item['stockQuantity'],'weight_grams':item['weightGrams'],'image_url':item['imageUrl'],'status':'ACTIVE'}
        refs={'category_id':('categories',item['categoryName']),'region_id':('tea_regions',item['proposedRegionName']),'store_id':('stores',store)}
        existing=seed.get_named('products',item['name'])
        if existing:
            for key,(table,name) in refs.items():
                ref=seed.get_named(table,name);assert ref and existing[key]==ref[IDS[table]], 'Existing product relation conflict'
            add('products',values);continue
        assert not seed.rows('products','slug='+seed.literal(item['slug'])), 'Product slug collision'
        fields=list(values)+list(refs)+['created_at','updated_at']
        vals=[str(v) if k in {'price','stock_quantity','weight_grams'} else seed.literal(v) for k,v in values.items()]
        vals += ['(SELECT `'+IDS[t]+'` FROM `'+DB+'`.`'+t+'` WHERE name='+seed.literal(n)+')' for t,n in refs.values()]
        statements.append('INSERT INTO `teasmart`.`products` ('+','.join('`'+f+'`' for f in fields)+') VALUES ('+','.join(vals+['NOW()','NOW()'])+');')
        created.append({'table':'products','name':item['name'],'demoSku':item['demoSku']})
    if args.apply and statements:
        assert args.ack_main, 'Explicit main approval switch required'
        assert seed.query("SELECT JSON_OBJECT('n',COUNT(*)) FROM information_schema.INNODB_TRX")[0]['n']==0, 'Active transaction; stop'
        assert snapshot()==before, 'Concurrent change; stop'
        seed.query('START TRANSACTION;\n'+'\n'.join(statements)+'\nCOMMIT;')
    after=snapshot()
    preserved={}
    for t,rows in before.items():
        current=set(encoded(r) for r in after[t]);preserved[t]=all(encoded(r) in current for r in rows)
        if t not in IDS:preserved[t]=preserved[t] and digest(rows)==digest(after[t])
    result={'database':DB,'mode':'APPLY' if args.apply else 'DRY_RUN','backup':backup['backup'],'backupSha256':backup['sha256'],'before':{t:len(r) for t,r in before.items()},'after':{t:len(r) for t,r in after.items()},'created' if args.apply else 'planned':created,'skipped':skipped,'oldRowsPreserved':preserved,'beforeDigests':{t:digest(r) for t,r in before.items()},'afterDigests':{t:digest(r) for t,r in after.items()},'schemaChanged':False}
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    assert all(preserved.values()), 'Preservation check failed; STOP, never automatically restore'
    print(json.dumps({'mode':result['mode'],'newOrPlanned':len(created),'skipped':len(skipped),'oldTablesPreserved':sum(preserved.values()),'catalogCounts':{t:len(after[t]) for t in IDS}}))

if __name__=='__main__':
    try:main()
    except Exception as error:
        # Fixed class only: no raw SQL, credentials, account data or database errors.
        print('APPEND_STOPPED: '+type(error).__name__,file=sys.stderr);sys.exit(1)
