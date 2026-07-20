def test_service_bundle_exposes_transport_neutral_wealth_service() -> None:
    from ft.runtime import ServiceBundle
    assert "wealth" in ServiceBundle.__dataclass_fields__
