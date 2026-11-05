package com.finance.tracker.backend
import kotlin.test.*
class SourceDigestParityTest {
  @Test fun digestNumbersMatchPython() {
    assertEquals("10058b8cd98a02ff73d5cd3092bb44f05b0d11148d0a81fde35c2a90673f1254", sourceDigest(Double.POSITIVE_INFINITY))
    assertEquals("4735d351ed0e14ba484a0d7a09335c3b8741258817529f5b4539111bbbaeb829", sourceDigest(Double.NEGATIVE_INFINITY))
    assertEquals("638577393ecb248f472c18a1a95104181a3188551615d763873393eaf4eff186", sourceDigest(Double.NaN))
    assertEquals("675b9062a0f09d152dfeb320b97c106479f7cb9b4f64d07ae8e50fc27693af2d", sourceDigest(1e20))
    assertEquals("6f2a4cc2979c5b0c77170a03f98dc070399f8c02fa1c21f00cf05689a95a7797", sourceDigest(1e-6))
    assertEquals("7bf21b3c3ebc8a257d2618abe4d23978afbc5fb90fc183ab67326150f8ba378c", sourceDigest(-0.0))
    assertEquals("c2272c0a862f11de35da66ec8be34349001ddebae43b85ab1dafb188f8ad7b30", sourceDigest(1.0))
  }
}
