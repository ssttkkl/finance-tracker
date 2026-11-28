from pathlib import Path
import sys
p=Path(sys.argv[1]);s=p.read_text().replace("const checkout = resolve(perfDirectory, '../../..');", "const checkout = process.cwd();")
start=s.find("        tool('python3', [join(perfDirectory, 'fixtures/large-cash.py')")
if start<0: start=s.index('        const diagnosticFixture')
end=s.index('        const keyPath',start)
s=s[:start]+'''        const sizes = [32, 128, 512];
        tool('python3', [join(perfDirectory,'diagnosis/small-mixed-cash-fixtures.py'),
            join(checkout,'compose/client-operations/perf/fixtures/large-cash.py'),fixture,backend]);
        evidence.fixture = {};
        for (const size of sizes) {
            const directory = join(fixture,String(size));
            const manifest = JSON.parse(await readFile(join(directory,'manifest.json'),'utf8'));
            assert.deepEqual(manifest.formats,{csv:2,xls:1});
            assert.equal(manifest.rows,2*size+4+size/4);
            for (const file of manifest.files) assert.equal(await sha256(join(directory,file.file)),file.sha256);
            evidence.fixture[size] = manifest;
        }

'''+s[end:]
start=s.index('        if (!legacyComparison) {\n            const [, controlCsv]')
end=s.index('    } catch (error) {\n        checks.completed = false;',start)
s=s[:start]+'''        const limits = JSON.parse(await readFile(join(perfDirectory,'budgets.json'),'utf8')).scenarios;
        const within = (id, measured) => {
            const {budgetMs,maxRssBytes} = limits.find(scenario=>scenario.id===id);
            return {limitMs:budgetMs,limitRss:maxRssBytes,meetsTime:measured.ms<=budgetMs,meetsRss:measured.rssBytes<=maxRssBytes};
        };
        for (const size of sizes) {
            const sizeWorkspace = (await run(['workspace','create','--name','Synthetic mixed '+size])).value.active_workspace_id;
            const stateScope=['--workspace',sizeWorkspace];
            const files=evidence.fixture[size].files;
            const prepared=(await run(['import','prepare',...files.map(file=>join(fixture,String(size),file.file)),...stateScope])).value;
            assert.equal(prepared.files.length,3);
            assert.ok(prepared.files.every(file=>file.status==='parsed'));
            const account=(await run(['import','account','create',prepared.batch_id,'--revision',prepared.revision,
                '--name','Synthetic mixed settlement','--type','cash','--currency','CNY,USD',...stateScope])).value.account;
            const choices=join(work,'choices-'+size+'.json');
            await writeFile(choices,JSON.stringify({expected_session_revision:Number(prepared.revision),previous_plan_id:null,
                account_selections:prepared.source_accounts.map(group=>({source_account_ref:group.source_account_ref,account_id:account.account_id,
                    expected_account_revision:Number(account.account_revision)})),row_choices:[],relation_choices:[],entry_choices:[]}));
            const reviewStart=observed.length;
            const review=await run(['import','review',prepared.batch_id,'--choices',choices,...stateScope]);
            const expectedRows=2*size+4, duplicate=size/4;
            assert.equal(review.value.can_commit,true);
            assert.equal(review.value.summary.create_posted,expectedRows);
            assert.equal(review.value.summary.duplicate,duplicate);
            assert.equal(review.value.summary.relations_accepted,size);
            assert.equal(review.value.summary.relations_failed,0);
            assert.equal(review.value.summary.skip,0);
            assert.equal(mutatingRequests(reviewStart).length,0);
            await record('diagnostic.mixed.review.'+size,review,{...within('large.review',review),sourceRows:evidence.fixture[size].rows,
                summary:review.value.summary,itemCounts:review.value.item_counts});
            const confirmed=await run(['import','confirm',prepared.batch_id,'--review',review.value.review_revision,...stateScope]);
            const receipt=confirmed.value.receipt;
            assert.equal(confirmed.value.items_complete,true);
            assert.equal(confirmed.value.batch_id,prepared.batch_id);
            assert.equal(receipt.summary.created_posted,expectedRows);
            assert.equal(receipt.summary.duplicate,duplicate);
            assert.equal(receipt.summary.relations_confirmed,size);
            const db=JSON.parse(psql(['-v',`workspace=${sizeWorkspace}`,'-v',`receipt=${receipt.receipt_id}`,'-f',join(perfDirectory,'large-receipt.sql')]));
            assert.equal(db.cny,expectedRows-1);
            assert.equal(db.usd,1);
            assert.equal(db.posted,expectedRows);
            assert.equal(db.exactPairs,size);
            assert.deepEqual(db.pairIds,receipt.write_set_result.relations.map(relation=>relation.id).sort());
            assert.deepEqual(db.rowOutcomes,{created:expectedRows});
            assert.deepEqual(db.receipts,[{id:receipt.receipt_id,idempotency_key:prepared.batch_id}]);
            const posts=writeRequests(prepared.batch_id);
            assert.equal(posts.length,1);
            assert.equal(posts[0].status,202);
            const reopened=(await run(['import','result',prepared.batch_id,...stateScope])).value;
            assert.deepEqual(reopened.page,confirmed.value.page);
            assert.deepEqual(reopened.receipt,receipt);
            await record('diagnostic.mixed.confirm.'+size,confirmed,{...within('large.confirm',confirmed),posted:db.posted,cny:db.cny,usd:db.usd,
                duplicate,exactPairs:db.exactPairs,submittedBytes:posts[0].bytes,uniqueReceipt:true,reopened:true});
        }
        checks.smallMixedCompleted=true;
        checks.completed=false;
'''+s[end:]
p.write_text(s)
