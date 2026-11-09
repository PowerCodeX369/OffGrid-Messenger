#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include "../main/link_rate_policy.h"

int main(void) {
    const int64_t second=1000;
    const int64_t now=100*second;
    /* Healthy hello receptions do NOT turn delayed application receipts into
       radio faults, even if ACK retry timer has expired. */
    for (int i=0;i<7;i++) {
        const int64_t t=now+i*850;
        const int64_t recent_hello=t-1800;
        assert(og_phy_recent_regular_contact(t,recent_hello));
        assert(!og_phy_should_fallback_on_app_timeout(t,recent_hello,0));
    }
    /* LR probes are not independent evidence of healthy normal-PHY traffic. */
    assert(og_phy_recent_regular_contact(now,now-2000));
    assert(!og_phy_recent_regular_contact(now,now-7000));
    assert(!og_phy_recent_regular_contact(now,0));
    /* A real outage does authorize a fallback, but not every retry. */
    const int64_t stale_hello=now-8500;
    assert(og_phy_should_fallback_on_app_timeout(now,stale_hello,0));
    assert(!og_phy_should_fallback_on_app_timeout(now+850,stale_hello,now+12000));
    assert(!og_phy_should_fallback_on_app_timeout(now+4500,stale_hello,now+12000));
    assert(og_phy_should_fallback_on_app_timeout(now+12001,stale_hello,now+12000));
    /* A peer coming back immediately prevents another app-timeout downgrade. */
    assert(!og_phy_should_fallback_on_app_timeout(now+13000,now+12500,now+12000));
    /* The existing real-distance rate ordering and recovery steps stay valid. */
    assert(og_lr_fallback(12,true)==6);
    assert(og_lr_fallback(6,true)==1);
    assert(og_lr_fallback(1,true)==50);
    assert(og_lr_fallback(50,true)==25);
    assert(og_lr_target(50,-58,OG_RATE_AUTO)==1);
    puts("PASS: stable PHY policy with healthy HELLO, stale peer, and cooldown");
}
