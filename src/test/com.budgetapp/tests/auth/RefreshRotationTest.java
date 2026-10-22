package tests.auth;


/**
 * Covers {@code POST /auth/refresh}.
 *
 * <p>The endpoint implements single-use refresh-token rotation: each call
 * consumes the presented token and issues a brand-new pair. That design
 * limits the damage of a stolen token — the moment either party uses it,
 * the other one's copy becomes worthless.
 */
class RefreshRotationTest {

}
