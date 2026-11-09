#ifndef OFFGRID_LINK_RATE_POLICY_H
#define OFFGRID_LINK_RATE_POLICY_H
/* Deliberately pure/testable policy; called only from dispatcher. */
#include <stdint.h>
#define OG_RATE_AUTO 0u
#define OG_RATE_RANGE 1u
#define OG_RATE_SPEED 2u
static inline uint8_t og_rate_target(uint8_t current, int32_t rssi, uint8_t mode) {
    if(mode==OG_RATE_RANGE || rssi<=-100)return 1;
    /* Speed profile relaxes thresholds only slightly; reliability is still
       protected by the firmware retry-driven 1-Mbps cooldown. */
    int high=(mode==OG_RATE_SPEED)?-61:-55;
    int med=(mode==OG_RATE_SPEED)?-78:-72;
    int high_drop=(mode==OG_RATE_SPEED)?-72:-65;
    int med_drop=(mode==OG_RATE_SPEED)?-85:-81;
    if(current==12){
        if(rssi<med_drop)return 1;
        if(rssi<high_drop)return 6;
        return 12;
    }
    if(current==6){
        if(rssi<med_drop)return 1;
        if(rssi>=high)return 12;
        return 6;
    }
    /* Upgrade in steps, 1 -> 6 -> 12, never 1 -> 12 immediately. */
    return rssi>=med?6:1;
}
#endif
