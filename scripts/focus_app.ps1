Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WinFront {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  public delegate bool EnumProc(IntPtr h, IntPtr l);

  public static IntPtr Target = IntPtr.Zero;

  public static string Find() {
    Target = IntPtr.Zero;
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      if (!IsWindowVisible(h)) return true;
      StringBuilder sb = new StringBuilder(256);
      GetWindowText(h, sb, 256);
      if (sb.ToString().Contains("LifeDesk")) { Target = h; return false; }
      return true;
    }, IntPtr.Zero);
    return Target == IntPtr.Zero ? "NOT_FOUND" : Target.ToString();
  }

  public static void Front() {
    if (Target == IntPtr.Zero) return;
    ShowWindow(Target, 9);
    SetWindowPos(Target, new IntPtr(-1), 0, 0, 0, 0, 0x0001 | 0x0002);
    uint pid;
    uint fg = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fg, true);
    SetForegroundWindow(Target);
    AttachThreadInput(me, fg, false);
    SetWindowPos(Target, new IntPtr(-2), 0, 0, 0, 0, 0x0001 | 0x0002);
  }
}
"@

$h = [WinFront]::Find()
Write-Host ("window=" + $h)
[WinFront]::Front()
Start-Sleep -Milliseconds 400
$fg = [WinFront]::GetForegroundWindow()
Write-Host ("foreground=" + $fg + " match=" + ($fg -eq [WinFront]::Target))
