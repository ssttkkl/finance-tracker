\set ON_ERROR_STOP on
begin read only;

with facts as (
    select * from cash_transactions where workspace_id = :'workspace' and deleted_at is null
), scoped_receipts as (
    select id, idempotency_key from cash_mutation_receipts
    where workspace_id = :'workspace' and operation_kind = 'cash_write_set'
), source_rows_without_id as (
    select distinct transaction_id from cash_source_bindings
    where workspace_id = :'workspace' and source_type = 'icbc_credit' and identity_kind = 'file_occurrence'
), mirror_pairs as (
    select r.id::text as relation_id,
        case when a.source_type = 'ccb_debit' then a.source_payload->>'序号'
             else b.source_payload->>'序号' end as bank_seq,
        case when a.source_type = 'alipay' then a.source_payload->>'交易订单号'
             else b.source_payload->>'交易订单号' end as payment_ref
    from transaction_relations r
    join facts a on a.id = r.primary_transaction_id
    join facts b on b.id = r.secondary_transaction_id
    join cash_transaction_components ca on ca.workspace_id = r.workspace_id and ca.id = r.primary_component_id
    join cash_transaction_components cb on cb.workspace_id = r.workspace_id and cb.id = r.secondary_component_id
    join cash_entry_members ea on ea.workspace_id = r.workspace_id and ea.transaction_id = a.id
    join cash_entry_members eb on eb.workspace_id = r.workspace_id and eb.transaction_id = b.id
    where r.workspace_id = :'workspace' and r.kind = 'payment_mirror' and r.state = 'confirmed' and r.active
      and ((a.source_type = 'ccb_debit' and b.source_type = 'alipay')
        or (b.source_type = 'ccb_debit' and a.source_type = 'alipay'))
      and a.amount = b.amount and a.currency = b.currency and a.currency = 'CNY'
      and ca.cash_transaction_id = a.id and cb.cash_transaction_id = b.id and ea.entry_id = eb.entry_id
), exact_pairs as (
    select * from mirror_pairs where bank_seq::bigint = right(payment_ref, 13)::bigint + 1
), row_outcomes as (
    select outcome, count(*) as count from cash_mutation_results
    where workspace_id = :'workspace' and receipt_id = :'receipt' and result_kind = 'row'
    group by outcome
)
select json_build_object(
    'cny', (select count(*) from facts where currency = 'CNY'),
    'usd', (select count(*) from facts where currency = 'USD'),
    'posted', (select count(*) from facts where state = 'posted'),
    'drafts', (select count(*) from facts where state <> 'posted'),
    'receipts', (select json_agg(to_json(scoped_receipts)) from scoped_receipts),
    'missingNativeIds', (select count(*) from source_rows_without_id),
    'relations', (select count(*) from transaction_relations where workspace_id = :'workspace'),
    'exactPairs', (select count(distinct bank_seq) from exact_pairs),
    'pairIds', (select json_agg(relation_id order by relation_id) from exact_pairs),
    'rowOutcomes', (select json_object_agg(outcome, count) from row_outcomes)
);

commit;
