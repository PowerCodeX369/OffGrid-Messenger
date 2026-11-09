from pathlib import Path
s=(Path(__file__).resolve().parents[1]/'main/main.c').read_text()
block=s.split('static void downgrade_on_missing_ack(void) {',1)[1].split('static uint8_t target_phy_for_link(void)',1)[0]
assert 'og_phy_should_fallback_on_app_timeout(t,last_regular_peer_ms,rate_hold_until_ms)' in block
assert 'if(link_mode==OG_RATE_RANGE || link_mode==OG_RATE_LR250 || link_mode==OG_RATE_LR500)return;' in block
assert 'if(!learn_or_observe_peer(e->mac,e->rssi,regular_frame))return;' in s
assert 'if(!og_phy_recent_regular_contact(now_ms(),last_regular_peer_ms))' in s
assert 'if(!og_phy_recent_regular_contact(t,last_regular_peer_ms))return;' in s
assert 'if(t-last_phy_change_ms<PHY_DWELL_MS)return;' in s
assert 't<rate_hold_until_ms || any_pending()' in s
assert 'if(!og_phy_recent_regular_contact(now_ms(),last_regular_peer_ms))' in s
assert 'lr_probe_downgrades_suppressed++;' in s
assert 'ack_rf_downgrades_suppressed++;' in s
assert 'if(link_mode==OG_RATE_RANGE)return;' in s
assert '(link_mode==OG_RATE_AUTO || link_mode==OG_RATE_SPEED)' in s
assert 'ESP_ERROR_CHECK(esp_now_register_recv_cb(on_receive));' in s
assert 'offgrid_ble_init(own_mac,pairing_key,pairing_key_present);' in s
print('PASS: driver paths, manual overrides, probe gating, and diagnostic counters')
