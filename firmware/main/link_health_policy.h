#ifndef OFFGRID_LINK_HEALTH_POLICY_H
#define OFFGRID_LINK_HEALTH_POLICY_H
#include <stdint.h>
#include <stdbool.h>
/* Changes to PHY are executed only by firmware dispatcher. This policy never
 * touches Wi-Fi callbacks, app messages, pairing keys, or RF test features. */
#define OG_HEALTH_MIN_GOOD 3
#define OG_HEALTH_CONFIRM_FRESH_MS 11000
#define OG_HEALTH_RSSI_FRESH_MS 12000
#define OG_HEALTH_PROBE_MS 2400
#define OG_HEALTH_PROBE_TIMEOUT_MS 1800
#define OG_HEALTH_HOLD_MS 11000
#define OG_HEALTH_DWELL_MS 4600

typedef struct {
    uint8_t confirmed_streak;
    uint8_t failure_streak;
    int64_t last_confirm_ms;
    int64_t last_real_rssi_ms;
    int64_t inhibit_up_until_ms;
    int64_t last_upgrade_ms;
    uint32_t confirmed_pings;
    uint32_t timed_out_pings;
} og_health_t;
static inline void og_health_reset(og_health_t *h){
    *h=(og_health_t){0};
}
static inline void og_health_real_rssi(og_health_t *h, int64_t now){
    h->last_real_rssi_ms=now;
}
static inline void og_health_confirm(og_health_t *h, int64_t now){
    if(h->confirmed_streak<100)h->confirmed_streak++;
    h->failure_streak=0;
    h->last_confirm_ms=now;
    h->confirmed_pings++;
}
static inline void og_health_upgraded(og_health_t *h,int64_t now){
    h->last_upgrade_ms=now;
    h->confirmed_streak=0; /* retest the NEW PHY before any further upgrade */
}
static inline void og_health_failure(og_health_t *h, int64_t now){
    h->confirmed_streak=0;
    if(h->failure_streak<100)h->failure_streak++;
    /* A new faster PHY that fails is blacklisted for longer; avoid repeatedly
     * oscillating across a barely-reachable rate boundary. */
    const bool failed_new_rate=h->last_upgrade_ms>0 && now-h->last_upgrade_ms<15000;
    h->inhibit_up_until_ms=now+(failed_new_rate?45000:OG_HEALTH_HOLD_MS);
    if(failed_new_rate)h->last_upgrade_ms=0;
    h->timed_out_pings++;
}
static inline bool og_health_can_up(const og_health_t *h,int64_t now,int64_t last_change,bool busy){
    return !busy && h->confirmed_streak>=OG_HEALTH_MIN_GOOD &&
        h->last_confirm_ms>0 && now-h->last_confirm_ms<=OG_HEALTH_CONFIRM_FRESH_MS &&
        h->last_real_rssi_ms>0 && now-h->last_real_rssi_ms<=OG_HEALTH_RSSI_FRESH_MS &&
        now>=h->inhibit_up_until_ms && now-last_change>=OG_HEALTH_DWELL_MS;
}
static inline uint8_t og_health_next_up(uint8_t current){
    switch(current){case 25:return 50;case 50:return 1;case 1:return 6;case 6:return 12;default:return 12;}
}
#endif
