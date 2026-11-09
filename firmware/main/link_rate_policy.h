#ifndef OFFGRID_LINK_RATE_POLICY_H
#define OFFGRID_LINK_RATE_POLICY_H
/* Deliberately pure/testable policy; called only from dispatcher. */
#include <stdint.h>
#include <stdbool.h>
#define OG_RATE_AUTO 0u
#define OG_RATE_RANGE 1u
#define OG_RATE_SPEED 2u
#define OG_RATE_LR500 3u
#define OG_RATE_LR250 4u
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
/* Explicit rate ordering: do not numerically compare encoded PHY rates. */
static inline int og_lr_rank(uint8_t rate) {
    switch(rate) {
        case 25: return 0;
        case 50: return 1;
        case 1: return 2;
        case 6: return 3;
        case 12: return 4;
        default: return -1;
    }
}
static inline uint8_t og_lr_fallback(uint8_t current, bool lr_available) {
    switch(current) {
        case 12: return 6;
        case 6: return 1;
        case 1: return lr_available ? 50 : 1;
        case 50: return lr_available ? 25 : 1;
        case 25: return lr_available ? 25 : 1;
        default: return 1;
    }
}
/* PHY codes: 25 and 50 denote 250/500 kbps, 1/6/12 denote Mbps.
 * This is called after a real RSSI sample on a peer. The failure detector
 * overrides this target and holds the connection at lower speeds. */
static inline uint8_t og_lr_target(uint8_t current, int32_t rssi, uint8_t mode) {
    if(mode==OG_RATE_LR250)return 25;
    if(mode==OG_RATE_LR500)return 50;
    if(mode==OG_RATE_RANGE)return 1;
    /* Upgrade thresholds deliberately conservative; no one-step 250k->12M. */
    int strong=mode==OG_RATE_SPEED?-59:-55;
    int moderate=mode==OG_RATE_SPEED?-77:-72;
    if(current==25){return rssi>=-88?50:25;}
    if(current==50){return rssi<=-101?25:(rssi>=-80?1:50);}
    if(current==1){return rssi<=-97?25:(rssi<=-85?50:(rssi>=moderate?6:1));}
    if(current==6){return rssi<=-85?1:(rssi>=strong?12:6);}
    if(current==12){return rssi<=-80?1:(rssi<=-65?6:12);}
    return 1;
}
#endif
