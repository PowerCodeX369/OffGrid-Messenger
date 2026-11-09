#include <assert.h>
#include <stdio.h>
#include "../main/link_rate_policy.h"
int main(void) {
    assert(og_rate_target(1,-24,OG_RATE_AUTO)==6);
    assert(og_rate_target(6,-24,OG_RATE_AUTO)==12);
    assert(og_rate_target(12,-24,OG_RATE_AUTO)==12);
    assert(og_rate_target(12,-68,OG_RATE_AUTO)==6);
    assert(og_rate_target(6,-83,OG_RATE_AUTO)==1);
    assert(og_rate_target(1,-90,OG_RATE_AUTO)==1);
    assert(og_rate_target(12,-24,OG_RATE_RANGE)==1);
    assert(og_rate_target(1,-76,OG_RATE_AUTO)==1);
    assert(og_rate_target(1,-76,OG_RATE_SPEED)==6);
    assert(og_rate_target(12,-127,OG_RATE_AUTO)==1);
    assert(og_rate_target(6,-67,OG_RATE_AUTO)==6);
    printf("PASS: Adaptive PHY 1/6/12 Mbps policy, thresholds, hysteresis and modes\n");
    return 0;
}
