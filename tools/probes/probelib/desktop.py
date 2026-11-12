"""Windows 客户端运行在独立 desktop，窗口与指针不会进入用户桌面。"""
import ctypes
from ctypes import wintypes
import os
import subprocess
from uuid import uuid4

from .autojs import ProbeError


class BackgroundDesktop:
    """拥有后台窗口 desktop；调用方在客户端退出后关闭，不切换输入桌面。"""

    def __init__(self):
        if os.name != 'nt':
            raise ProbeError('--background 当前使用 Windows desktop，需要 Windows')
        self.name = 'UsbProbe_' + uuid4().hex
        self.user32 = ctypes.WinDLL('user32', use_last_error=True)
        self.user32.CreateDesktopW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR,
                                              ctypes.c_void_p, wintypes.DWORD,
                                              wintypes.DWORD, ctypes.c_void_p]
        self.user32.CreateDesktopW.restype = wintypes.HANDLE
        self.user32.CloseDesktop.argtypes = [wintypes.HANDLE]
        self.user32.CloseDesktop.restype = wintypes.BOOL
        self.user32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.user32.OpenInputDesktop.restype = wintypes.HANDLE
        self.user32.GetUserObjectInformationW.argtypes = [wintypes.HANDLE, ctypes.c_int,
                                                        ctypes.c_void_p, wintypes.DWORD,
                                                        ctypes.POINTER(wintypes.DWORD)]
        self.user32.GetUserObjectInformationW.restype = wintypes.BOOL
        self.input_name = self._input_name()
        # 只申请创建窗口、菜单、读取/写入对象和枚举权，不申请切换 desktop。
        self.handle = self.user32.CreateDesktopW(self.name, None, None, 0, 0x00C7, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())

    def _input_name(self):
        handle = self.user32.OpenInputDesktop(0, False, 0x0001)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            name = ctypes.create_unicode_buffer(256)
            needed = wintypes.DWORD()
            if not self.user32.GetUserObjectInformationW(handle, 2, name, ctypes.sizeof(name), ctypes.byref(needed)):
                raise ctypes.WinError(ctypes.get_last_error())
            return name.value
        finally:
            self.user32.CloseDesktop(handle)

    def start(self, argv, directory, output, environment):
        return DesktopProcess(self.name, argv, directory, output, environment)

    def state(self):
        current = self._input_name()
        return dict(mode='windows-desktop', name=self.name, inputBefore=self.input_name,
                    inputNow=current, isolated=current != self.name)

    def close(self):
        if self.handle:
            if not self.user32.CloseDesktop(self.handle):
                raise ctypes.WinError(ctypes.get_last_error())
            self.handle = None


class _StartupInfo(ctypes.Structure):
    _fields_ = [('cb', wintypes.DWORD), ('lpReserved', wintypes.LPWSTR),
                ('lpDesktop', wintypes.LPWSTR), ('lpTitle', wintypes.LPWSTR),
                ('dwX', wintypes.DWORD), ('dwY', wintypes.DWORD),
                ('dwXSize', wintypes.DWORD), ('dwYSize', wintypes.DWORD),
                ('dwXCountChars', wintypes.DWORD), ('dwYCountChars', wintypes.DWORD),
                ('dwFillAttribute', wintypes.DWORD), ('dwFlags', wintypes.DWORD),
                ('wShowWindow', wintypes.WORD), ('cbReserved2', wintypes.WORD),
                ('lpReserved2', ctypes.POINTER(ctypes.c_byte)),
                ('hStdInput', wintypes.HANDLE), ('hStdOutput', wintypes.HANDLE),
                ('hStdError', wintypes.HANDLE)]


class _ProcessInfo(ctypes.Structure):
    _fields_ = [('hProcess', wintypes.HANDLE), ('hThread', wintypes.HANDLE),
                ('dwProcessId', wintypes.DWORD), ('dwThreadId', wintypes.DWORD)]


class DesktopProcess:
    """Runtime 使用的进程 adapter；标准 subprocess 不传递 lpDesktop。"""

    def __init__(self, desktop, argv, directory, output, environment):
        import msvcrt
        self.kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel32.CreateProcessW.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR,
            ctypes.c_void_p, ctypes.c_void_p, wintypes.BOOL, wintypes.DWORD,
            ctypes.c_void_p, wintypes.LPCWSTR, ctypes.POINTER(_StartupInfo), ctypes.POINTER(_ProcessInfo)]
        self.kernel32.CreateProcessW.restype = wintypes.BOOL
        self.kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.kernel32.WaitForSingleObject.restype = wintypes.DWORD
        self.kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        self.kernel32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        self.stdin = open(os.devnull, 'r')
        handles = [msvcrt.get_osfhandle(self.stdin.fileno()), msvcrt.get_osfhandle(output.fileno())]
        previous = [os.get_handle_inheritable(handle) for handle in handles]
        startup = _StartupInfo(cb=ctypes.sizeof(_StartupInfo), lpDesktop=desktop,
                               dwFlags=subprocess.STARTF_USESTDHANDLES | subprocess.STARTF_USESHOWWINDOW,
                               wShowWindow=0, hStdInput=handles[0], hStdOutput=handles[1], hStdError=handles[1])
        process = _ProcessInfo()
        command = ctypes.create_unicode_buffer(subprocess.list2cmdline([str(value) for value in argv]))
        variables = ctypes.create_unicode_buffer('\0'.join(f'{key}={value}' for key, value in sorted(environment.items()))+'\0\0')
        try:
            for handle in handles:
                os.set_handle_inheritable(handle, True)
            if not self.kernel32.CreateProcessW(None, command, None, None, True,
                    subprocess.CREATE_NO_WINDOW | 0x0400, variables, str(directory), ctypes.byref(startup), ctypes.byref(process)):
                raise ctypes.WinError(ctypes.get_last_error())
        finally:
            for handle, inherited in zip(handles, previous):
                os.set_handle_inheritable(handle, inherited)
            if not process.hProcess:
                self.stdin.close()
        self.kernel32.CloseHandle(process.hThread)
        self.handle, self.pid, self.returncode = process.hProcess, process.dwProcessId, None

    def poll(self):
        if self.returncode is not None:
            return self.returncode
        state = self.kernel32.WaitForSingleObject(self.handle, 0)
        if state == 0x0102:
            return None
        if state != 0:
            raise ctypes.WinError(ctypes.get_last_error())
        code = wintypes.DWORD()
        if not self.kernel32.GetExitCodeProcess(self.handle, ctypes.byref(code)):
            raise ctypes.WinError(ctypes.get_last_error())
        self.returncode = code.value
        self.kernel32.CloseHandle(self.handle)
        self.handle = None
        return self.returncode

    def wait(self, timeout):
        if self.poll() is None:
            state = self.kernel32.WaitForSingleObject(self.handle, int(timeout * 1000))
            if state == 0x0102:
                raise subprocess.TimeoutExpired(str(self.pid), timeout)
            if state != 0:
                raise ctypes.WinError(ctypes.get_last_error())
        return self.poll()

    def kill(self):
        if self.poll() is None and not self.kernel32.TerminateProcess(self.handle, 1):
            raise ctypes.WinError(ctypes.get_last_error())
