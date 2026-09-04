using System.ComponentModel.Composition;
using WinTak.Framework.Docking;
using WinTak.Framework.Tools;
using WinTak.Framework.Tools.Attributes;

namespace ICUVideoStreamer
{
    [Button("ICUVideoStreamer_VideoStreamButton", "WinTAK-ICU",
        LargeImage = "pack://application:,,,/ICUVideoStreamer;component/assets/ic_launcher.png",
        SmallImage = "pack://application:,,,/ICUVideoStreamer;component/assets/ic_launcher.png",
        Tab        = "Home",
        TabGroup   = "VISTA Tools",
        ToolTip    = "Open the Video Stream plugin")]
    [Export(typeof(Button))]
    public class VideoStreamButton : Button
    {
        private readonly IDockingManager _dockingManager;

        [ImportingConstructor]
        public VideoStreamButton(IDockingManager dockingManager)
        {
            _dockingManager = dockingManager;
        }

        protected override void OnClick()
        {
            base.OnClick();
            var pane = _dockingManager.GetDockPane(VideoStreamDockPane.ID);
            pane?.Activate();
        }
    }
}
