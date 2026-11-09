#include <assert.h>
#include <stdio.h>
#include "../main/link_rate_policy.h"
int main(void){
 assert(og_lr_rank(25)<og_lr_rank(50));
 assert(og_lr_rank(50)<og_lr_rank(1));
 assert(og_lr_rank(1)<og_lr_rank(6));
 assert(og_lr_rank(6)<og_lr_rank(12));
 assert(og_lr_fallback(12,true)==6);
 assert(og_lr_fallback(6,true)==1);
 assert(og_lr_fallback(1,true)==50);
 assert(og_lr_fallback(50,true)==25);
 assert(og_lr_fallback(25,true)==25);
 assert(og_lr_fallback(1,false)==1);
 assert(og_lr_target(12,-42,OG_RATE_AUTO)==12);
 assert(og_lr_target(12,-72,OG_RATE_AUTO)==6);
 assert(og_lr_target(6,-87,OG_RATE_AUTO)==1);
 assert(og_lr_target(1,-92,OG_RATE_AUTO)==50);
 assert(og_lr_target(1,-104,OG_RATE_AUTO)==25);
 assert(og_lr_target(50,-105,OG_RATE_AUTO)==25);
 assert(og_lr_target(25,-83,OG_RATE_AUTO)==50);
 assert(og_lr_target(25,-42,OG_RATE_LR250)==25);
 assert(og_lr_target(12,-42,OG_RATE_LR500)==50);
 assert(og_lr_target(12,-42,OG_RATE_RANGE)==1);
 printf("LR policy tests passed\n");
 return 0;
}
