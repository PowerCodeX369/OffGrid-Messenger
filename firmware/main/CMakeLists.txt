# Driver component was split out of `driver` on newer ESP-IDF releases.
if(EXISTS "${IDF_PATH}/components/esp_driver_usb_serial_jtag")
    set(offgrid_usb_dep esp_driver_usb_serial_jtag)
else()
    set(offgrid_usb_dep driver)
endif()
if(EXISTS "${IDF_PATH}/components/esp_vfs")
    set(offgrid_vfs_dep esp_vfs)
else()
    set(offgrid_vfs_dep vfs)
endif()
idf_component_register(SRCS "main.c" INCLUDE_DIRS "."
  REQUIRES esp_wifi esp_event esp_netif nvs_flash esp_timer
    ${offgrid_usb_dep} ${offgrid_vfs_dep})
