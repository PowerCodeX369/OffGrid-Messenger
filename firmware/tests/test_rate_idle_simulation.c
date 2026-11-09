#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "../main/link_rate_policy.h"
/* This is a policy-level model, NOT an ESP-NOW hardware test. */
int main(void) {
    int64_t last_regular=1000;
    int64_t hold_until=0, last_change=1000;
    uint8_t phy=12;
    int changes=0, app_timeouts=0;
    /* Normal HELLOs every 1800ms, RSSI varies a few dB around -58;
       inject missing app receipt every 11 sec plus an unsolicited LR probe. */
    for(int64_t t=2000;t<180000;t+=200){
        if(t%1800==200)last_regular=t;
        int32_t rssi=(t%14000<5000)?-58:(t%14000<10000)?-61:-56;
        if(t%11000==200){
            app_timeouts++;
            if(og_phy_should_fallback_on_app_timeout(t,last_regular,hold_until)){
                uint8_t next=og_lr_fallback(phy,true);
                if(next!=phy){phy=next;changes++;last_change=t;}
                hold_until=t+12000;
            }
        }
        if(t%17000==200 && !og_phy_recent_regular_contact(t,last_regular)){
            phy=25;changes++;last_change=t;
        }
        uint8_t desired=og_lr_target(phy,rssi,OG_RATE_AUTO);
        if(desired!=phy && og_phy_recent_regular_contact(t,last_regular) &&
           t-last_change>=7000 && t>=hold_until){
            phy=desired;changes++;last_change=t;
        }
    }
    assert(app_timeouts>=10);
    assert(phy==12);
    assert(changes==0);
    /* Genuine loss: once peer has gone missing, fallback is allowed, and then
       only once per hold period. */
    int64_t now=200000, stale=180000;
    assert(og_phy_should_fallback_on_app_timeout(now,stale,0));
    assert(!og_phy_should_fallback_on_app_timeout(now+800,stale,now+12000));
    puts("PASS: idle strong-link model has zero PHY switches over 3 min; loss falls back with hold");
}
