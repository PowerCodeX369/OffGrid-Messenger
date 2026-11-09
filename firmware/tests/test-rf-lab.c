#include <assert.h>
#include <stdio.h>
#include "../main/link_rate_policy.h"
/* Rate-policy-only test. Synthetic RSSI is NOT a radio sensitivity test. */
int main(void) {
    assert(og_lr_target(12,-35,OG_RATE_AUTO)==12);
    assert(og_lr_target(12,-75,OG_RATE_AUTO)==6);
    assert(og_lr_target(6,-89,OG_RATE_AUTO)==1);
    assert(og_lr_target(1,-92,OG_RATE_AUTO)==50);
    assert(og_lr_target(1,-105,OG_RATE_AUTO)==25);
    assert(og_lr_target(25,-30,OG_RATE_AUTO)==50); /* upgrades stepwise */
    assert(og_lr_target(12,-100,OG_RATE_LR250)==25);
    puts("RF_LAB_POLICY_PASS");
}
