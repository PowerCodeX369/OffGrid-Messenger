#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "ble_binary.h"
int main(void){
 uint8_t packet[OFFGRID_BIN_MAX_WRITE],decoded[180];uint16_t token=0,sz=0;
 const int lens[]={1,2,20,160,180};
 for(size_t j=0;j<sizeof(lens)/sizeof(lens[0]);j++){
  int n=lens[j];packet[0]=0xB7;packet[1]=0x47;packet[2]=1;packet[3]=0x34;packet[4]=0x12;
  for(int i=0;i<n;i++)packet[5+i]=(uint8_t)(i*17+7);
  assert(offgrid_bin_decode(packet,n+5,&token,decoded,&sz));
  assert(token==0x1234 && sz==n && memcmp(decoded,packet+5,n)==0);
  assert(!offgrid_bin_decode(packet,5,&token,decoded,&sz));
  packet[2]=2;assert(!offgrid_bin_decode(packet,n+5,&token,decoded,&sz));packet[2]=1;
  packet[3]=0;packet[4]=0;assert(!offgrid_bin_decode(packet,n+5,&token,decoded,&sz));
 }
 packet[0]=0xB7;packet[1]=0x47;packet[2]=1;packet[3]=1;packet[4]=0;
 assert(!offgrid_bin_decode(packet,186,&token,decoded,&sz));
 printf("PASS binary BLE C decoder: bounds, malformed version/token and roundtrip 180 bytes\n");
 return 0;
}
