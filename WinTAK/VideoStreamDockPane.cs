using System;
using System.Collections.ObjectModel;
using System.ComponentModel.Composition;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using System.Xml;
using Prism.Commands;
using WinTak.Common.Location;
using WinTak.Common.Services;
using WinTak.CursorOnTarget.Services;
using WinTak.Framework.Docking;
using WinTak.Framework.Docking.Attributes;
using ICUVideoStreamer.Cot;
using ICUVideoStreamer.Models;
using ICUVideoStreamer.Services;

namespace ICUVideoStreamer
{
    [DockPane(ID, "WinTAK-ICU", Content = typeof(VideoStreamView))]
    [Export(typeof(IDockPane))]
    public class VideoStreamDockPane : DockPane
    {
        internal const string ID = "ICUVideoStreamer_VideoStreamDockPane";

        // WinTAK-injected services
        private readonly ILocationService   _locationService;
        private readonly ICotMessageSender  _cotSender;

        // Plugin-owned services
        private AppSettings          _settings;
        private StreamingService     _streamingService;
        private CameraPreviewService _previewService;
        private KlvService           _klvService;

        // Timers
        private DispatcherTimer _clockTimer;
        private DispatcherTimer _streamTimer;
        private DispatcherTimer _sensorCotTimer; // periodically re-sends video CoT with updated sensor data
        private DateTime        _streamStart;

        // Location (updated from ILocationService or manual override)
        private double _lat;
        private double _lon;
        private double _alt;

        // ── Observable properties ────────────────────────────────────────────────

        private string _clockText = "";
        public string ClockText { get => _clockText; private set => SetProperty(ref _clockText, value); }

        private string _statusText = "Ready";
        public string StatusText { get => _statusText; private set => SetProperty(ref _statusText, value); }

        private string _bitrateText = "";
        public string BitrateText { get => _bitrateText; private set => SetProperty(ref _bitrateText, value); }

        private Brush _streamDotColor = Brushes.Red;
        public Brush StreamDotColor { get => _streamDotColor; private set => SetProperty(ref _streamDotColor, value); }

        private string _streamElapsedText = "";
        public string StreamElapsedText { get => _streamElapsedText; private set => SetProperty(ref _streamElapsedText, value); }

        private bool _isStreaming;
        public bool IsStreaming
        {
            get => _isStreaming;
            private set
            {
                if (SetProperty(ref _isStreaming, value))
                    OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs(nameof(CanChangeSource)));
            }
        }

        public bool CanChangeSource => !IsStreaming;

        private System.Windows.Media.ImageSource _frozenPreviewBitmap;
        public System.Windows.Media.ImageSource PreviewBitmap => _previewService?.Bitmap ?? _frozenPreviewBitmap;

        private Visibility _recBadgeVisibility = Visibility.Collapsed;
        public Visibility RecBadgeVisibility { get => _recBadgeVisibility; private set => SetProperty(ref _recBadgeVisibility, value); }

        private string _streamUrlDisplay = "";
        public string StreamUrlDisplay { get => _streamUrlDisplay; private set => SetProperty(ref _streamUrlDisplay, value); }

        // Credential-free public stream URL shown as a clickable link over the preview
        // (bottom-left). Clicking copies it to the clipboard.
        private string _streamUrlLink = "";
        public string StreamUrlLink
        {
            get => _streamUrlLink;
            private set
            {
                if (SetProperty(ref _streamUrlLink, value))
                    OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs(nameof(StreamUrlLinkVisibility)));
            }
        }

        public Visibility StreamUrlLinkVisibility =>
            string.IsNullOrWhiteSpace(_streamUrlLink) ? Visibility.Collapsed : Visibility.Visible;

        public string BuildDateText { get; } =
            "Built " + System.IO.File.GetLastWriteTime(
                System.Reflection.Assembly.GetExecutingAssembly().Location)
                .ToString("yyyy-MM-dd HH:mm");

        // GPS status (replaces TAK server status — WinTAK manages the connection)
        private Brush _gpsDotColor = Brushes.Red;
        public Brush GpsDotColor { get => _gpsDotColor; private set => SetProperty(ref _gpsDotColor, value); }

        private string _gpsStatusText = "No GPS";
        public string GpsStatusText { get => _gpsStatusText; private set => SetProperty(ref _gpsStatusText, value); }

        private string _latText = "0.0000000";
        public string LatText { get => _latText; private set => SetProperty(ref _latText, value); }

        private string _lonText = "0.0000000";
        public string LonText { get => _lonText; private set => SetProperty(ref _lonText, value); }

        private string _altText = "0.0";
        public string AltText { get => _altText; private set => SetProperty(ref _altText, value); }

        private string _selectedCamera = "";
        public string SelectedCamera
        {
            get => _selectedCamera;
            set
            {
                if (SetProperty(ref _selectedCamera, value))
                {
                    _settings.VideoDevice = value ?? "";
                    UpdatePreview();
                }
            }
        }

        private bool _isScreenShare;
        public bool IsScreenShare
        {
            get => _isScreenShare;
            set
            {
                if (SetProperty(ref _isScreenShare, value))
                {
                    _settings.EnableScreenShare = value;
                    UpdatePreview();
                }
            }
        }

        private string _manualLat = "";
        public string ManualLat { get => _manualLat; set => SetProperty(ref _manualLat, value); }

        private string _manualLon = "";
        public string ManualLon { get => _manualLon; set => SetProperty(ref _manualLon, value); }

        private string _manualAlt = "";
        public string ManualAlt { get => _manualAlt; set => SetProperty(ref _manualAlt, value); }

        private bool _isManualLocationExpanded;
        public bool IsManualLocationExpanded
        {
            get => _isManualLocationExpanded;
            set => SetProperty(ref _isManualLocationExpanded, value);
        }

        // Sensor heading (0–360°) — KLV tag 5 + CoT sensor azimuth, drives the UI arrow
        private double _sensorHeading;
        public double SensorHeading
        {
            get => _sensorHeading;
            set
            {
                if (SetProperty(ref _sensorHeading, value))
                {
                    _klvService?.UpdateHeading(value);
                    SendSensorCot(); // update map cone immediately
                }
            }
        }

        // Sensor elevation angle (−90 to +90°) — KLV tag 19 + CoT sensor elevation
        private double _sensorElevation;
        public double SensorElevation
        {
            get => _sensorElevation;
            set
            {
                if (SetProperty(ref _sensorElevation, value))
                {
                    _klvService?.UpdateElevation(value);
                    SendSensorCot(); // update map cone immediately
                }
            }
        }

        public ObservableCollection<string> CameraDevices { get; } = new ObservableCollection<string>();

        // ── Commands ─────────────────────────────────────────────────────────────

        public DelegateCommand StartStreamCommand          { get; }
        public DelegateCommand StopStreamCommand           { get; }
        public DelegateCommand OpenSettingsCommand         { get; }
        public DelegateCommand RefreshDevicesCommand       { get; }
        public DelegateCommand ApplyLocationCommand        { get; }
        public DelegateCommand ToggleManualLocationCommand { get; }
        public DelegateCommand CopyStreamUrlCommand        { get; }

        // ── Constructor ──────────────────────────────────────────────────────────

        [ImportingConstructor]
        public VideoStreamDockPane(ILocationService locationService, ICotMessageSender cotSender)
        {
            _locationService = locationService;
            _cotSender       = cotSender;

            StartStreamCommand          = new DelegateCommand(OnStartStream, () => !IsStreaming);
            StopStreamCommand           = new DelegateCommand(OnStopStream,  () => IsStreaming);
            OpenSettingsCommand         = new DelegateCommand(OnOpenSettings);
            RefreshDevicesCommand       = new DelegateCommand(OnRefreshDevices);
            ApplyLocationCommand        = new DelegateCommand(OnApplyLocation);
            ToggleManualLocationCommand = new DelegateCommand(() =>
                IsManualLocationExpanded = !IsManualLocationExpanded);
            CopyStreamUrlCommand        = new DelegateCommand(OnCopyStreamUrl);

            _settings = AppSettings.Load();
            InitializeServices();
            StartClockTimer();
            OnRefreshDevices();
            RefreshStreamUrlDisplay();

            // Subscribe to WinTAK's GPS
            _locationService.PositionChanged          += OnPositionChanged;
            _locationService.ConnectionStatusChanged  += OnGpsConnectionChanged;

            // Seed current position
            SyncPositionFromService();
            UpdateGpsStatus();

            // Broadcast the sensor CoT immediately and every 10 s regardless of
            // streaming state so other ATAK devices always see the sensor marker.
            SendSensorCot();
            StartSensorCotTimer();
        }

        // ── WinTAK GPS integration ────────────────────────────────────────────────

        private void OnPositionChanged(object sender, PositionChangedEventArgs e)
        {
            _lat = e.Position.Latitude;
            _lon = e.Position.Longitude;
            _alt = e.Position.Altitude;
            _klvService?.UpdateLocation(_lat, _lon, _alt);

            Application.Current?.Dispatcher.InvokeAsync(() =>
            {
                LatText = _lat.ToString("F7");
                LonText = _lon.ToString("F7");
                AltText = _alt.ToString("F1");
                GpsDotColor  = Brushes.LimeGreen;
                GpsStatusText = "GPS Active";
            });
        }

        private void OnGpsConnectionChanged(object sender, EventArgs e)
        {
            Application.Current?.Dispatcher.InvokeAsync(UpdateGpsStatus);
        }

        private void SyncPositionFromService()
        {
            try
            {
                var pos = _locationService.GetGpsPosition();
                if (pos != null)
                {
                    _lat = pos.Latitude;
                    _lon = pos.Longitude;
                    _alt = pos.Altitude;
                    LatText = _lat.ToString("F7");
                    LonText = _lon.ToString("F7");
                    AltText = _alt.ToString("F1");
                }
            }
            catch { }
        }

        private void UpdateGpsStatus()
        {
            bool hasGps = _locationService.HasConnections;
            GpsDotColor   = hasGps ? Brushes.LimeGreen : Brushes.Red;
            GpsStatusText = hasGps ? "GPS Active" : "No GPS";
        }

        // ── Service setup ────────────────────────────────────────────────────────

        private void InitializeServices()
        {
            _streamingService?.Dispose();

            _streamingService = new StreamingService(_settings);
            _streamingService.BitrateUpdated += kbps =>
                Application.Current?.Dispatcher.InvokeAsync(() =>
                    BitrateText = kbps > 0 ? kbps + " kbps" : "");
            _streamingService.StatusChanged += s =>
                Application.Current?.Dispatcher.InvokeAsync(() => StatusText = s);
            _streamingService.Stopped += () =>
                Application.Current?.Dispatcher.InvokeAsync(OnStreamStopped);

            // KlvService is independent of transport settings — create once
            if (_klvService == null)
                _klvService = new KlvService();

            // Always sync sensor metadata from settings (user may have changed them)
            _klvService.SensorName = _settings.SensorName;
            _klvService.UpdateFov(_settings.SensorHFov, _settings.SensorVFov);
            _klvService.UpdateHeading(_sensorHeading);
            _klvService.UpdateElevation(_sensorElevation);
            _klvService.UpdateLocation(_lat, _lon, _alt);

            RefreshStreamUrlDisplay();
        }

        private void StartClockTimer()
        {
            _clockTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _clockTimer.Tick += (s, e) => ClockText = DateTime.Now.ToString("HH:mm:ss");
            _clockTimer.Start();
        }

        // ── Stream control ───────────────────────────────────────────────────────

        private void UpdatePreview()
        {
            bool shouldPreview = !string.IsNullOrEmpty(SelectedCamera)
                                 && !IsScreenShare
                                 && !IsStreaming;

            if (shouldPreview)
            {
                _previewService?.Stop();
                _previewService = new CameraPreviewService();
                _previewService.StatusChanged += s =>
                    Application.Current?.Dispatcher.InvokeAsync(() => StatusText = s);
                _previewService.Start(SelectedCamera, _settings.ResolvedFfmpegPath,
                    Application.Current.Dispatcher);
                OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs(nameof(PreviewBitmap)));
            }
            else
            {
                _previewService?.Stop();
                _previewService = null;
                OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs(nameof(PreviewBitmap)));
            }
        }

        private void OnStartStream()
        {
            _frozenPreviewBitmap = _previewService?.Bitmap; // keep last frame visible while streaming
            _previewService?.Stop();
            _previewService = null;
            OnPropertyChanged(new System.ComponentModel.PropertyChangedEventArgs(nameof(PreviewBitmap)));

            // Start KLV pipe server — must be listening before FFmpeg connects to it
            _klvService?.UpdateLocation(_lat, _lon, _alt);
            _klvService?.UpdateHeading(_sensorHeading);
            _klvService?.UpdateElevation(_sensorElevation);
            _klvService?.Start();

            // Give DirectShow time to fully release the camera before FFmpeg opens it
            System.Threading.Tasks.Task.Delay(600).ContinueWith(_ =>
                Application.Current?.Dispatcher.InvokeAsync(StartStreamCore));
        }

        private void StartStreamCore()
        {
            _streamingService.Start(_klvService?.PipeName);
            if (!_streamingService.IsStreaming) return;

            IsStreaming         = true;
            StreamStatusHub.IsStreaming = true; // drive the floating status widget
            StreamDotColor      = Brushes.LimeGreen;
            StatusText          = "Streaming";
            RecBadgeVisibility  = _settings.EnableRecording ? Visibility.Visible : Visibility.Collapsed;
            RefreshStreamUrlDisplay();
            StartStreamTimer();

            // Fire an immediate update so the video URL appears in the sensor CoT
            // as soon as the stream starts (timer is already running from constructor).
            SendSensorCot();

            StartStreamCommand.RaiseCanExecuteChanged();
            StopStreamCommand.RaiseCanExecuteChanged();
        }

        // ── Sensor CoT (map FOV cone) ────────────────────────────────────────────

        /// <summary>
        /// Sends a b-m-p-s-p-loc sensor CoT with FOV cone and embedded video link.
        /// Uses uid + "-SENSOR" so other ATAK devices see it as a NEW, separate
        /// entity on the map rather than merging it into WinTAK's own SA marker.
        /// </summary>
        private void SendSensorCot()
        {
            var (uid, callsign) = GetSelfIdentity();
            // Append "-SENSOR" so this is a distinct map entity from the WinTAK
            // device dot.  ICU does the same by being a physically separate device
            // with its own UID; we replicate that by creating a dedicated sensor UID.
            SendCot(CotBuilder.BuildVideoEvent(
                uid + "-SENSOR", callsign, _settings.PublicStreamUrl, _lat, _lon,
                sensorAzimuth:   _sensorHeading,
                sensorElevation: _sensorElevation,
                hfov:            _settings.SensorHFov,
                vfov:            _settings.SensorVFov,
                sensorModel:     _settings.SensorName));
        }

        private void StartSensorCotTimer()
        {
            _sensorCotTimer?.Stop();
            _sensorCotTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
            _sensorCotTimer.Tick += (s, e) => SendSensorCot();
            _sensorCotTimer.Start();
        }

        private void StopSensorCotTimer()
        {
            _sensorCotTimer?.Stop();
            _sensorCotTimer = null;
        }

        private void OnStopStream()
        {
            _streamingService.Stop();
            OnStreamStopped();
        }

        private void OnStreamStopped()
        {
            _klvService?.Stop();

            IsStreaming        = false;
            StreamStatusHub.IsStreaming = false; // drive the floating status widget
            StreamDotColor     = Brushes.Red;
            BitrateText        = "";
            RecBadgeVisibility = Visibility.Collapsed;
            StopStreamTimer();
            StartStreamCommand.RaiseCanExecuteChanged();
            StopStreamCommand.RaiseCanExecuteChanged();

            RefreshStreamUrlDisplay();

            // Delay preview restart so the streaming FFmpeg fully releases the camera
            System.Threading.Tasks.Task.Delay(1000).ContinueWith(_ =>
                Application.Current?.Dispatcher.InvokeAsync(() =>
                {
                    _frozenPreviewBitmap = null;
                    UpdatePreview();
                }));
        }

        // ── CoT sending via WinTAK ───────────────────────────────────────────────

        private void SendCot(string cotXml)
        {
            try
            {
                var doc = new XmlDocument();
                doc.LoadXml(cotXml);
                _cotSender.Send(doc);
            }
            catch { }
        }

        // ── Timers ───────────────────────────────────────────────────────────────

        private void StartStreamTimer()
        {
            _streamStart = DateTime.Now;
            _streamTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _streamTimer.Tick += (s, e) =>
                StreamElapsedText = (DateTime.Now - _streamStart).ToString(@"hh\:mm\:ss");
            _streamTimer.Start();
        }

        private void StopStreamTimer()
        {
            _streamTimer?.Stop();
            _streamTimer = null;
            StreamElapsedText = "";
        }

        // ── WinTAK identity ──────────────────────────────────────────────────────

        private (string uid, string callsign) GetSelfIdentity()
        {
            try
            {
                var doc = _locationService.GetPositionDocument();
                if (doc?.DocumentElement != null)
                {
                    string uid = doc.DocumentElement.GetAttribute("uid");
                    var contact = doc.SelectSingleNode("//contact") as System.Xml.XmlElement;
                    string callsign = contact?.GetAttribute("callsign") ?? "";
                    if (!string.IsNullOrEmpty(uid))
                        return (uid, callsign);
                }
            }
            catch { }
            return (_settings.Uid, "WinTAK-ICU");
        }

        // ── Settings ─────────────────────────────────────────────────────────────

        private void OnOpenSettings()
        {
            var win = new SettingsWindow(_settings);
            win.Owner = Application.Current?.MainWindow;
            if (win.ShowDialog() == true)
            {
                _settings.Save();
                InitializeServices();
                RefreshStreamUrlDisplay();
            }
        }

        private void OnRefreshDevices()
        {
            var devices = StreamingService.EnumerateVideoDevices(_settings.FfmpegPath);
            Application.Current?.Dispatcher.InvokeAsync(() =>
            {
                CameraDevices.Clear();
                foreach (string d in devices)
                    CameraDevices.Add(d);
                if (CameraDevices.Count > 0 && string.IsNullOrEmpty(SelectedCamera))
                    SelectedCamera = CameraDevices[0]; // setter calls UpdatePreview
                else
                    UpdatePreview();
            });
        }

        private void OnApplyLocation()
        {
            if (double.TryParse(ManualLat, out double lat)) _lat = lat;
            if (double.TryParse(ManualLon, out double lon)) _lon = lon;
            if (double.TryParse(ManualAlt, out double alt)) _alt = alt;
            LatText    = _lat.ToString("F7");
            LonText    = _lon.ToString("F7");
            AltText    = _alt.ToString("F1");
            _klvService?.UpdateLocation(_lat, _lon, _alt);
            StatusText = "Location overridden manually";
        }

        private void RefreshStreamUrlDisplay()
        {
            StreamUrlDisplay = _settings?.DisplayStreamUrl ?? "";
            StreamUrlLink    = _settings?.PublicStreamUrl ?? "";
        }

        private void OnCopyStreamUrl()
        {
            string url = StreamUrlLink;
            if (string.IsNullOrWhiteSpace(url)) return;
            try
            {
                Clipboard.SetText(url);
                StatusText = "Stream URL copied to clipboard";
            }
            catch
            {
                // Clipboard can transiently fail if another process holds it — non-fatal.
                StatusText = "Could not copy stream URL";
            }
        }
    }
}
