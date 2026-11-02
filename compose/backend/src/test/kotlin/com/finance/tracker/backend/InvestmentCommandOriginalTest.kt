package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentCommandOriginalTest {
    private fun service(commands: MutableList<InvestmentCommand>) = InvestmentCommandApplication { command -> commands.add(command); buildJsonObject { put("ok", true); put("message", command.recordType) } }
    @Test fun test_all_investment_commands_build_decimal_dtos() {
        val commands = mutableListOf<InvestmentCommand>(); val s = service(commands)
        s.buy("AAPL.US", "1.25", "100.50", "0.25", "USD", "IBKR", "n", "2026-06-01")
        s.sell("AAPL.US", "0.25", "110", "0", "USD", "IBKR", "", null)
        s.swap("Kraken", "BTC", "0.1", "ETH", "2", "USD", "", null)
        s.deposit("10", "USD", "IBKR", "", null); s.withdraw("2", "USD", "IBKR", "", null)
        s.dividend("AAPL.US", "1.50", "USD", "IBKR", "", null); s.checkinTicker("AAPL.US", "3.5", "90", "USD", "IBKR", "", null); s.checkinCash("8.75", "USD", "IBKR", "", null)
        assertEquals(listOf("buy", "sell", "swap", "deposit", "withdraw", "dividend", "checkin_ticker", "checkin_cash"), commands.map { it.recordType })
        assertEquals(exactCashAmount("1.25"), commands[0].quantity); assertEquals(exactCashAmount("100.50"), commands[0].price); assertEquals(exactCashAmount("2"), commands[2].toQuantity); assertEquals(exactCashAmount("8.75"), commands.last().amount)
    }
    @Test fun test_investment_service_rejects_non_finite_numbers_before_repository() { val commands = mutableListOf<InvestmentCommand>(); assertContains(assertFailsWith<IllegalArgumentException> { service(commands).deposit("NaN", "USD", "IBKR", "", null) }.message!!, "finite"); assertEquals(emptyList(), commands) }
}
