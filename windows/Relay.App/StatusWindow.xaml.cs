using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace Linqavo.App;

public sealed partial class StatusWindow : Window
{
    private readonly Action _open;
    private bool _visible;

    public StatusWindow(Action open)
    {
        _open = open;
        InitializeComponent();
        Title = Strings.Get("AppName");
        AutomationProperties.SetName(OpenButton, Strings.Get("TrayOpen"));
        ToolTipService.SetToolTip(OpenButton, Strings.Get("TrayOpen"));
        AutomationProperties.SetName(HideButton, Strings.Get("WindowClose"));
        ToolTipService.SetToolTip(HideButton, Strings.Get("WindowClose"));
        Surface.KeyDown += (_, args) =>
        {
            if (args.Key != Windows.System.VirtualKey.Escape) return;
            args.Handled = true;
            HideStatus();
        };
        Surface.FlowDirection = System.Globalization.CultureInfo.CurrentUICulture.TextInfo.IsRightToLeft
            ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        AppWindow.IsShownInSwitchers = false;
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.SetBorderAndTitleBar(true, false);
            presenter.IsAlwaysOnTop = true;
            presenter.IsResizable = false;
            presenter.IsMaximizable = false;
        }
        Activated += (_, args) =>
        {
            if (args.WindowActivationState == WindowActivationState.Deactivated)
            {
                HideStatus();
            }
        };
    }

    internal void Update(string status, string detail, string rates, Brush brush)
    {
        Status.Text = status;
        Detail.Text = detail;
        Detail.Visibility = detail.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        Rates.Text = rates;
        Rates.Visibility = rates.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        Mark.Foreground = brush;
        if (_visible) Position();
    }

    internal void ShowAtTray()
    {
        _visible = true;
        Position();
        AppWindow.Show();
        Activate();
        Position();
        OpenButton.Focus(FocusState.Programmatic);
    }

    private void Position()
    {
        var scale = Surface.XamlRoot?.RasterizationScale ?? 1.0;
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var margin = (int)(10 * scale);
        var width = Math.Min((int)(360 * scale), Math.Max(1, area.Width - 2 * margin));
        Card.Measure(new Windows.Foundation.Size(width / scale - 4, double.PositiveInfinity));
        var height = Math.Min((int)Math.Ceiling((Card.DesiredSize.Height + 4) * scale), area.Height - 2 * margin);
        var x = Surface.FlowDirection == FlowDirection.RightToLeft ? area.X + margin : area.X + area.Width - width - margin;
        var bounds = new RectInt32(x, area.Y + area.Height - height - margin, width, height);
        if (AppWindow.Position.X != bounds.X || AppWindow.Position.Y != bounds.Y ||
            AppWindow.Size.Width != width || AppWindow.Size.Height != height)
            AppWindow.MoveAndResize(bounds);
    }

    private void HideStatus()
    {
        _visible = false;
        AppWindow.Hide();
    }

    private void HideMini(object sender, RoutedEventArgs e) => HideStatus();
    private void OpenDashboard(object sender, RoutedEventArgs e)
    {
        HideStatus();
        _open();
    }
}
