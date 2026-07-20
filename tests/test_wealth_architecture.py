from pathlib import Path


def test_wealth_domain_and_application_do_not_depend_on_adapters_or_transport():
    root = Path(__file__).parents[1] / "src" / "ft"
    for path in (root / "domain" / "wealth.py", root / "domain" / "wealth_calculation.py", root / "application" / "wealth.py"):
        text = path.read_text()
        assert "sqlalchemy" not in text
        assert "market_data" not in text
        assert "ft.cli" not in text
        assert "http" not in text.lower()
