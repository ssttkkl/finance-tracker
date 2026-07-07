def test_ccxt_is_importable():
    """ccxt 必须已安装，交易所同步依赖它。"""
    import ccxt
    assert hasattr(ccxt, "kraken")
