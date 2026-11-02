"""Export original portfolio refresh merges without replacing production results."""
from __future__ import annotations
import argparse
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path
from ft.application.portfolio_refresh import _merge_last_success
from ft.domain.investment import PortfolioDTO, PortfolioAccountDTO, PortfolioPositionDTO, PortfolioPeriodBaselineDTO
from ft.serialization import json_value


def cases():
    base = PortfolioPositionDTO('AAPL.US', Decimal('1'), Decimal('5'), 'USD', False,
        current_price=Decimal('10'), market_value=Decimal('10'), profit=Decimal('5'), quote_status='complete', quote_reason='ok', quote_currency='USD',
        quote_observed_at=datetime(2026, 8, 1, tzinfo=timezone.utc), quote_session='regular', display_currency='CNY', display_market_value=Decimal('70'),
        usd_market_value=Decimal('10'), fx_rate=Decimal('7'), fx_status='complete', fx_reason='ok', period_profit=Decimal('2'), period_profit_rate=Decimal('0.2'),
        period_baselines=(PortfolioPeriodBaselineDTO('Broker', 'AAPL.US', datetime(2026, 7, 1, tzinfo=timezone.utc)),), display_name='Apple')
    def portfolio(*positions, **totals):
        return PortfolioDTO((PortfolioAccountDTO('Broker', 'USD', positions),), **totals)
    prior = portfolio(base, total_market_value=Decimal('70'), total_profit=Decimal('35'), total_profit_rate=Decimal('0.5'), period_profit=Decimal('2'), period_profit_rate=Decimal('0.2'), period_baselines=base.period_baselines)
    missing = PortfolioPositionDTO('AAPL.US', Decimal('2'), Decimal('6'), 'USD', False, quote_status='partial', quote_reason='provider_error')
    inputs = [(None, prior), (prior, prior), (prior, portfolio()), (prior, PortfolioDTO(()))]
    for price in [None, '0', '-0.00', '1.123456789012345678', '11']:
        for market in [None, '0', '-0', '22']:
            for shares in ['2', '-2', '0', '1.000000000000000001']:
                p=replace(missing,current_price=None if price is None else Decimal(price),market_value=None if market is None else Decimal(market),shares=Decimal(shares))
                inputs.append((prior,portfolio(p)))
    for field,values in dict(quote_currency=[None,'','USD','HKD'],cost_currency=['USD','usd','HKD'],display_currency=[None,'','CNY','USD'],
                            fx_rate=[None,'0','-0','7.000000000000000001'],period_profit=[None,'0','4'],period_profit_rate=[None,'0','0.25'],
                            quote_session=[None,'','unknown','post_market'],quote_status=[None,'partial','stale'],ticker=['aapl.us','OTHER.US']).items():
        for value in values:
            if field in ['fx_rate','period_profit','period_profit_rate'] and value is not None:value=Decimal(value)
            inputs.append((prior,portfolio(replace(missing,**{field:value}))))
    inputs += [(prior,portfolio(replace(missing,period_baselines=()))),(prior,portfolio(replace(missing,period_baselines=base.period_baselines))),
               (prior,PortfolioDTO((PortfolioAccountDTO('Renamed','USD',(missing,)),))),
               (prior,PortfolioDTO((PortfolioAccountDTO('Broker','HKD',(missing,)),)))]
    for total in [None,Decimal('0'),Decimal('-0.00'),Decimal('12')]:
        for field in ['total_market_value','total_profit','total_profit_rate','period_profit','period_profit_rate']:
            inputs.append((prior,portfolio(missing,**{field:total})))
    inputs += [(portfolio(replace(base,display_currency=None,display_market_value=None)),portfolio(replace(missing,shares=Decimal('-2')))),
               (prior,portfolio(replace(missing,shares=Decimal('0'),total_cost=Decimal('0')))),
               (prior,portfolio(missing,replace(base,ticker='SECOND.US',quote_currency='HKD',display_currency=None,display_market_value=None)))]
    cash=replace(base,ticker='usd',is_cash=True,shares=Decimal('10'),total_cost=Decimal('10'),current_price=Decimal('1'),market_value=Decimal('10'),profit=Decimal('0'),display_currency=None,display_market_value=None,quote_currency='USD')
    for cash_shares in ['0','10','-10']:
        for equity_price in [None,'0','-0.00','11']:
            equity=replace(missing,current_price=None if equity_price is None else Decimal(equity_price))
            current_cash=replace(cash,shares=Decimal(cash_shares),market_value=Decimal(cash_shares),usd_market_value=Decimal(cash_shares),total_cost=Decimal(cash_shares))
            old=portfolio(replace(base,display_currency=None,display_market_value=None),cash)
            inputs.append((old,portfolio(equity,current_cash)))
            inputs.append((old,PortfolioDTO((PortfolioAccountDTO('Broker','USD',(equity,)),PortfolioAccountDTO('Second','USD',(current_cash,))))))
    inputs += [(prior,replace(prior,total_market_value=Decimal('70.00'))),
               (prior,replace(prior,accounts=(replace(prior.accounts[0],positions=(replace(base,quote_observed_at=base.quote_observed_at.astimezone(timezone(timedelta(hours=8)))),)),))),
               (prior,replace(prior,accounts=(replace(prior.accounts[0],name='10.0'),)))]
    return inputs


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError('Use an absolute external output')
    outputs=[]
    for i,(previous,incoming) in enumerate(cases()):
        result=_merge_last_success(previous,incoming)
        outputs.append(dict(name=f'refresh_{i:03d}',previous=json_value(previous),incoming=json_value(incoming),expected=json_value(result),equal=previous==incoming))
    args.output.write_text(json.dumps(outputs,ensure_ascii=True,indent=2)+'\n');print(len(outputs),'original complete merges')
