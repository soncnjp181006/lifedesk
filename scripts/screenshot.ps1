# Capture the LifeDesk window CONTENT directly (PrintWindow) - works even when occluded.
param([string]$OutFile = "target\live-dashboard.png")
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WinCap {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int max);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr hWnd, StringBuilder sb, int max);
  public struct RECT { public int Left, Top, Right, Bottom; }

  // The JavaFX stage: title contains "LifeDesk" but its window class is NOT
  // the console class (the "start" console window is also titled LifeDesk).
  public static IntPtr FindAppWindow() {
    IntPtr best = IntPtr.Zero;
    long bestArea = 0;
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      var cls = new StringBuilder(64);
      GetClassName(h, cls, 64);
      if (cls.ToString() == "ConsoleWindowClass") return true;
      var sb = new StringBuilder(256);
      GetWindowText(h, sb, 256);
      if (!sb.ToString().Contains("LifeDesk")) return true;
      WinCap.RECT r;
      if (!GetWindowRect(h, out r)) return true;
      long area = (long)(r.Right - r.Left) * (r.Bottom - r.Top);
      if (area > bestArea) { bestArea = area; best = h; }
      return true;
    }, IntPtr.Zero);
    return best;
  }
}
"@

$hwnd = [WinCap]::FindAppWindow()
if ($hwnd -eq [IntPtr]::Zero) {
    Write-Host "NO_LIFEDESK_WINDOW"
    exit 1
}
$rect = New-Object WinCap+RECT
[WinCap]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$w = $rect.Right - $rect.Left
$h = $rect.Bottom - $rect.Top
if ($w -le 0 -or $h -le 0 -or $w -lt 600) { Write-Host "BAD_SIZE ($w x $h)"; exit 1 }

$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
# PW_RENDERFULLCONTENT (3): renders DirectComposition/JavaFX windows correctly
$ok = [WinCap]::PrintWindow($hwnd, $hdc, 3)
$g.ReleaseHdc($hdc)
$g.Dispose()
if (-not $ok) { $bmp.Dispose(); Write-Host "PRINTWINDOW_FAILED"; exit 1 }
$bmp.Save($OutFile)
$bmp.Dispose()
Write-Host "SAVED $OutFile ($w x $h)"
