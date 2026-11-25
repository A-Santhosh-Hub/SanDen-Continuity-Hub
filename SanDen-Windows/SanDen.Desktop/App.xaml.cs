using System.IO;
using System.Runtime.InteropServices;
using System.Windows;

namespace SanDen.Desktop;

public partial class App : Application
{
    [DllImport("shell32.dll", SetLastError = true)]
    private static extern int SetCurrentProcessExplicitAppUserModelID([MarshalAs(UnmanagedType.LPWStr)] string AppID);

    protected override void OnStartup(StartupEventArgs e)
    {
        DispatcherUnhandledException += (_, args) =>
        {
            try
            {
                var logDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SanDen");
                Directory.CreateDirectory(logDir);
                File.WriteAllText(Path.Combine(logDir, "crash.log"), args.Exception.ToString());
            }
            catch { }
        };

        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            try
            {
                var logDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SanDen");
                Directory.CreateDirectory(logDir);
                File.WriteAllText(Path.Combine(logDir, "crash_domain.log"), args.ExceptionObject?.ToString() ?? "Unknown");
            }
            catch { }
        };

        try
        {
            _ = SetCurrentProcessExplicitAppUserModelID("SanStudio.SanDen.NativeHub.2");
        }
        catch
        {
            // Ignore on older shells
        }

        base.OnStartup(e);
    }
}
