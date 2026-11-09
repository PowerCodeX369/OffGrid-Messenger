#include <assert.h>
#include <stdio.h>
#include "../main/link_health_policy.h"
#include "../main/link_rate_policy.h"
int main(void){
    og_health_t h={0};
    int64_t now=100000;
    og_health_real_rssi(&h,now);
    /* A great RSSI alone must NEVER authorize 250k -> 500k. */
    assert(!og_health_can_up(&h,now+5000,now-20000,false));
    og_health_confirm(&h,now+100);og_health_confirm(&h,now+200);
    assert(!og_health_can_up(&h,now+5200,now-20000,false));
    og_health_confirm(&h,now+300);
    assert(og_health_can_up(&h,now+5200,now-20000,false));
    assert(!og_health_can_up(&h,now+5200,now-20000,true));
    assert(og_health_next_up(25)==50);
    assert(og_health_next_up(50)==1);
    assert(og_health_next_up(1)==6);
    assert(og_health_next_up(6)==12);
    /* A missed authenticated ping immediately revokes the upgrade permission. */
    og_health_failure(&h,now+5200);
    assert(h.confirmed_streak==0 && h.failure_streak==1);
    for(int i=0;i<3;i++)og_health_confirm(&h,now+5300+i*100);
    assert(!og_health_can_up(&h,now+6500,now-20000,false));
    /* Cooldown + proven good transmissions: permission returns. */
    assert(og_health_can_up(&h,now+16400,now+9000,false)==false); /* confirmations stale */
    og_health_real_rssi(&h,now+13000);
    for(int i=0;i<3;i++)og_health_confirm(&h,now+14000+i*100);
    assert(og_health_can_up(&h,now+16500,now+9000,false));
    assert(!og_health_can_up(&h,now+17000,now+16100,false)); /* dwell */
    assert(!og_health_can_up(&h,now+27000,now+9000,false)); /* stale RSSI */
    assert(og_lr_fallback(12,true)==6);
    assert(og_lr_fallback(6,true)==1);
    assert(og_lr_fallback(1,true)==50);
    assert(og_lr_fallback(50,true)==25);
    assert(og_lr_fallback(25,true)==25);
    /* A recently attempted higher rate that fails is held for longer. */
    og_health_upgraded(&h,now+30000);
    og_health_failure(&h,now+31000);
    og_health_real_rssi(&h,now+32000);
    for(int i=0;i<3;i++)og_health_confirm(&h,now+32000+i*100);
    assert(!og_health_can_up(&h,now+46000,now+30000,false));
    og_health_real_rssi(&h,now+76000);
    for(int i=0;i<3;i++)og_health_confirm(&h,now+76000+i*100);
    assert(og_health_can_up(&h,now+78000,now+30000,false));
    puts("PASS: authenticated probe gating, stale-RSSI rejection, cooldown, one-step promotion, LR fallback");
}
