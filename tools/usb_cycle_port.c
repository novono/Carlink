#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <winioctl.h>
#include <cfgmgr32.h>
#include <initguid.h>
#include <devpkey.h>
#include <usbioctl.h>

#include <stdio.h>
#include <wchar.h>

static DWORD map_config_error(CONFIGRET result) {
    return CM_MapCrToWin32Err(result, ERROR_NOT_FOUND);
}

static BOOL get_string_property(
    DEVINST node,
    const DEVPROPKEY *key,
    WCHAR *buffer,
    ULONG buffer_bytes
) {
    DEVPROPTYPE type = 0;
    CONFIGRET result = CM_Get_DevNode_PropertyW(
        node,
        key,
        &type,
        (PBYTE)buffer,
        &buffer_bytes,
        0
    );
    if (result != CR_SUCCESS) {
        SetLastError(map_config_error(result));
        return FALSE;
    }
    return TRUE;
}

static BOOL cycle_usb_port(PCWSTR instance_id, DWORD *port_number) {
    DEVINST node = 0;
    CONFIGRET result = CM_Locate_DevNodeW(
        &node,
        (DEVINSTID_W)instance_id,
        CM_LOCATE_DEVNODE_NORMAL
    );
    if (result != CR_SUCCESS) {
        SetLastError(map_config_error(result));
        return FALSE;
    }

    WCHAR hub_instance_id[MAX_DEVICE_ID_LEN] = {0};
    BOOL hub_found = FALSE;
    UINT32 connection_index = 0;

    while (!hub_found) {
        WCHAR service_name[MAX_PATH] = {0};
        if (!get_string_property(
                node,
                &DEVPKEY_Device_Service,
                service_name,
                sizeof(service_name))) {
            return FALSE;
        }

        hub_found = wcsstr(service_name, L"USBHUB") != NULL
            || wcsstr(service_name, L"usbhub") != NULL;
        if (hub_found) {
            if (!get_string_property(
                    node,
                    &DEVPKEY_Device_InstanceId,
                    hub_instance_id,
                    sizeof(hub_instance_id))) {
                return FALSE;
            }
            break;
        }

        DEVPROPTYPE address_type = 0;
        ULONG address_size = sizeof(connection_index);
        result = CM_Get_DevNode_PropertyW(
            node,
            &DEVPKEY_Device_Address,
            &address_type,
            (PBYTE)&connection_index,
            &address_size,
            0
        );
        if (result != CR_SUCCESS) {
            SetLastError(map_config_error(result));
            return FALSE;
        }

        WCHAR parent_instance_id[MAX_DEVICE_ID_LEN] = {0};
        if (!get_string_property(
                node,
                &DEVPKEY_Device_Parent,
                parent_instance_id,
                sizeof(parent_instance_id))) {
            return FALSE;
        }
        result = CM_Locate_DevNodeW(
            &node,
            parent_instance_id,
            CM_LOCATE_DEVNODE_NORMAL
        );
        if (result != CR_SUCCESS) {
            SetLastError(map_config_error(result));
            return FALSE;
        }
    }

    if (!hub_found || connection_index == 0) {
        SetLastError(ERROR_NOT_FOUND);
        return FALSE;
    }

    ULONG interface_list_size = 0;
    result = CM_Get_Device_Interface_List_SizeW(
        &interface_list_size,
        (LPGUID)&GUID_DEVINTERFACE_USB_HUB,
        hub_instance_id,
        CM_GET_DEVICE_INTERFACE_LIST_PRESENT
    );
    if (result != CR_SUCCESS) {
        SetLastError(map_config_error(result));
        return FALSE;
    }

    PWSTR interface_list = HeapAlloc(
        GetProcessHeap(),
        HEAP_ZERO_MEMORY,
        interface_list_size * sizeof(WCHAR)
    );
    if (interface_list == NULL) {
        SetLastError(ERROR_NOT_ENOUGH_MEMORY);
        return FALSE;
    }

    result = CM_Get_Device_Interface_ListW(
        (LPGUID)&GUID_DEVINTERFACE_USB_HUB,
        hub_instance_id,
        interface_list,
        interface_list_size,
        CM_GET_DEVICE_INTERFACE_LIST_PRESENT
    );
    if (result != CR_SUCCESS) {
        DWORD error = map_config_error(result);
        HeapFree(GetProcessHeap(), 0, interface_list);
        SetLastError(error);
        return FALSE;
    }

    HANDLE hub = CreateFileW(
        interface_list,
        GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL,
        NULL
    );
    HeapFree(GetProcessHeap(), 0, interface_list);
    if (hub == INVALID_HANDLE_VALUE) {
        return FALSE;
    }

    USB_CYCLE_PORT_PARAMS parameters = {connection_index, 0};
    DWORD bytes_returned = 0;
    BOOL success = DeviceIoControl(
        hub,
        IOCTL_USB_HUB_CYCLE_PORT,
        &parameters,
        sizeof(parameters),
        &parameters,
        sizeof(parameters),
        &bytes_returned,
        NULL
    );
    DWORD error = success ? parameters.StatusReturned : GetLastError();
    CloseHandle(hub);
    if (!success || parameters.StatusReturned != ERROR_SUCCESS) {
        SetLastError(error);
        return FALSE;
    }

    *port_number = connection_index;
    return TRUE;
}

int wmain(int argc, WCHAR **argv) {
    if (argc != 2) {
        fwprintf(stderr, L"usage: usb_cycle_port.exe <USB instance ID>\n");
        return 2;
    }

    DWORD port_number = 0;
    if (!cycle_usb_port(argv[1], &port_number)) {
        DWORD error = GetLastError();
        fwprintf(stderr, L"USB port cycle failed (Win32 %lu)\n", error);
        return error == ERROR_ACCESS_DENIED ? 5 : 1;
    }

    wprintf(L"USB port %lu cycle requested\n", port_number);
    return 0;
}
