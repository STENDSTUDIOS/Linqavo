using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using Windows.System;

namespace Linqavo.App;

internal sealed class TrayMenuWindow : Window
{
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out PointInt32 position);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);

    private readonly StackPanel _rows = new() { Padding = new Thickness(6), Spacing = 2 };
    private readonly List<Button> _buttons = new();
    private readonly Border _surface;

    public TrayMenuWindow(Action open, Action disconnect, Action updates, Action exit)
    {
        var resources = Application.Current.Resources;
        Title = Strings.Get("AppName");
        AppWindow.IsShownInSwitchers = false;
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.SetBorderAndTitleBar(false, false);
            presenter.IsAlwaysOnTop = true;
            presenter.IsResizable = presenter.IsMaximizable = presenter.IsMinimizable = false;
        }
        _surface = new Border
        {
            Background = (Brush)resources["SettingsSurface"], BorderBrush = (Brush)resources["HairlineBrush"],
            BorderThickness = new Thickness(1), CornerRadius = (CornerRadius)resources["ControlCorner"],
            RequestedTheme = ElementTheme.Dark,
            FlowDirection = System.Globalization.CultureInfo.CurrentUICulture.TextInfo.IsRightToLeft
                ? FlowDirection.RightToLeft : FlowDirection.LeftToRight,
            Child = new ScrollViewer { Content = _rows, HorizontalContentAlignment = HorizontalAlignment.Stretch,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, IsTabStop = false },
        };
        Content = _surface;
        AddItem("TrayOpen", "\uE8A7", open);
        AddItem("Disconnect", "\uE8CD", disconnect);
        AddItem("UpdateManual", "\uE895", updates);
        _rows.Children.Add(new Border { Height = 1, Background = (Brush)resources["HairlineBrush"],
            Margin = new Thickness(8, 3, 8, 3) });
        AddItem("TrayExit", "\uE8BB", exit);
        _surface.KeyDown += (_, args) =>
        {
            var current = _buttons.FindIndex(b => b.FocusState != FocusState.Unfocused);
            switch (args.Key)
            {
                case VirtualKey.Escape: AppWindow.Hide(); break;
                case VirtualKey.Down: _buttons[(current + 1) % _buttons.Count].Focus(FocusState.Keyboard); break;
                case VirtualKey.Up: _buttons[(current + _buttons.Count - 1) % _buttons.Count].Focus(FocusState.Keyboard); break;
                case VirtualKey.Home: _buttons[0].Focus(FocusState.Keyboard); break;
                case VirtualKey.End: _buttons[^1].Focus(FocusState.Keyboard); break;
                default: return;
            }
            args.Handled = true;
        };
        Activated += (_, args) =>
        {
            if (args.WindowActivationState == WindowActivationState.Deactivated) AppWindow.Hide();
        };
    }

    private void AddItem(string key, string glyph, Action run)
    {
        var label = Strings.Get(key);
        var row = new Grid { ColumnSpacing = 12 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition());
        row.Children.Add(new FontIcon { Glyph = glyph, FontSize = 16,
            Foreground = (Brush)Application.Current.Resources["AccentBrush"] });
        var text = new TextBlock { Text = label, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(text, 1);
        row.Children.Add(text);
        var button = new Button { Content = row, Padding = new Thickness(12, 8, 12, 8), MinHeight = 40,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0),
            HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetName(button, label);
        button.Click += (_, _) => { AppWindow.Hide(); run(); };
        _buttons.Add(button);
        _rows.Children.Add(button);
    }

    internal void ShowAtPointer()
    {
        var hasPointer = GetCursorPos(out var anchor);
        var area = DisplayArea.GetFromPoint(anchor, DisplayAreaFallback.Primary).WorkArea;
        if (!hasPointer) anchor = new PointInt32(area.X + area.Width - 1, area.Y + area.Height - 1);
        AppWindow.Move(anchor);
        var scale = GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / 96.0;
        _rows.Measure(new Windows.Foundation.Size(double.PositiveInfinity, double.PositiveInfinity));
        var width = Math.Min(area.Width, (int)Math.Ceiling(Math.Max(216, _rows.DesiredSize.Width + 4) * scale));
        _rows.Measure(new Windows.Foundation.Size(width / scale - 2, double.PositiveInfinity));
        var height = Math.Min(area.Height, (int)Math.Ceiling((_rows.DesiredSize.Height + 4) * scale));
        var x = Math.Clamp(anchor.X, area.X, area.X + area.Width - width);
        var y = anchor.Y + height <= area.Y + area.Height ? anchor.Y : anchor.Y - height;
        AppWindow.MoveAndResize(new RectInt32(x, Math.Clamp(y, area.Y, area.Y + area.Height - height), width, height));
        AppWindow.Show();
        Activate();
        _buttons[0].Focus(FocusState.Programmatic);
    }
}
