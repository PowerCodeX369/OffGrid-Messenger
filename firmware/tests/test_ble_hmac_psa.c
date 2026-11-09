/* Host test against EXACT firmware HMAC helper.
 * Stub PSA functions implement the documented API using OpenSSL HMAC.
 * Not a substitute for a full ESP-IDF 6.1 build. */
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <openssl/hmac.h>
#include "../main/ble_auth_psa.h"
static uint8_t temporary_key[32];
static bool loaded = false;
static int destroy_count = 0;
static bool fail_import = false;
static bool fail_mac = false;
void psa_set_key_type(psa_key_attributes_t *a,uint32_t v){a->type=v;}
void psa_set_key_bits(psa_key_attributes_t *a,uint32_t v){a->bits=v;}
void psa_set_key_usage_flags(psa_key_attributes_t *a,uint32_t v){a->usage=v;}
void psa_set_key_algorithm(psa_key_attributes_t *a,uint32_t v){a->algorithm=v;}
void psa_reset_key_attributes(psa_key_attributes_t *a){memset(a,0,sizeof(*a));}
psa_status_t psa_crypto_init(void){return PSA_SUCCESS;}
psa_status_t psa_import_key(const psa_key_attributes_t *a,const uint8_t *key,size_t n,psa_key_id_t *out){
 if(fail_import)return PSA_ERROR_GENERIC_ERROR;
 assert(a->type==PSA_KEY_TYPE_HMAC && a->bits==256 && a->usage==PSA_KEY_USAGE_SIGN_MESSAGE);
 assert(a->algorithm==PSA_ALG_HMAC(PSA_ALG_SHA_256) && n==32 && !loaded);
 memcpy(temporary_key,key,32);loaded=true;*out=42;return PSA_SUCCESS;
}
psa_status_t psa_mac_compute(psa_key_id_t id,psa_algorithm_t alg,const uint8_t *msg,size_t len,uint8_t *mac,size_t maxout,size_t *outlen){
 assert(id==42 && loaded && alg==PSA_ALG_HMAC(PSA_ALG_SHA_256) && maxout>=32);
 if(fail_mac)return PSA_ERROR_GENERIC_ERROR;
 unsigned n=0;assert(HMAC(EVP_sha256(),temporary_key,32,msg,len,mac,&n));*outlen=n;return PSA_SUCCESS;
}
psa_status_t psa_destroy_key(psa_key_id_t id){assert(id==42 && loaded);memset(temporary_key,0,32);loaded=false;destroy_count++;return PSA_SUCCESS;}
int main(void){
 /* RFC 4231 HMAC-SHA256 case 1 with a 32-byte key adapted below. */
 const uint8_t key[32]={0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,
 0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,
 0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b,0x0b};
 const uint8_t msg[]={ 'H','i',' ','T','h','e','r','e' };
 uint8_t out[32]={0},ref[32]={0};
 unsigned ref_n=0;assert(HMAC(EVP_sha256(),key,32,msg,sizeof(msg),ref,&ref_n)&&ref_n==32);
 assert(offgrid_ble_hmac_sha256(key,msg,sizeof(msg),out));
 assert(memcmp(out,ref,32)==0 && destroy_count==1 && !loaded);
 fail_import=true;assert(!offgrid_ble_hmac_sha256(key,msg,sizeof(msg),out));
 assert(destroy_count==1 && !loaded);fail_import=false;
 fail_mac=true;assert(!offgrid_ble_hmac_sha256(key,msg,sizeof(msg),out));
 assert(destroy_count==2 && !loaded);fail_mac=false;
 const char domain[]="OffGridBLE2";const uint8_t nonce[16]={0};
 uint8_t payload[sizeof(domain)-1+sizeof(nonce)]={0};
 memcpy(payload,domain,sizeof(domain)-1);memcpy(payload+sizeof(domain)-1,nonce,sizeof(nonce));
 assert(offgrid_ble_hmac_sha256(key,payload,sizeof(payload),out));
 assert(destroy_count==3 && !loaded);
 puts("PASS: PSA HMAC-SHA256 helper, failure paths, key cleanup, BLE domain");return 0;
}
