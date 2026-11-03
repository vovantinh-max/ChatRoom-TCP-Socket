using System;
using System.IO;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Media.Capture;
using Windows.Media.Capture.Frames;
using Windows.Storage.Streams;

public static class TcpChatCamera
{
    public static void Run(int parentPid)
    {
        using (var parent = Process.GetProcessById(parentPid))
        {
            parent.EnableRaisingEvents = true;
            parent.Exited += (sender, args) => Environment.Exit(0);
            if (!parent.HasExited) StreamFrames().GetAwaiter().GetResult();
        }
    }

    private static async Task Wait(IAsyncAction action)
    {
        while (action.Status == AsyncStatus.Started) await Task.Delay(5);
        action.GetResults();
    }

    private static async Task<T> Result<T>(IAsyncOperation<T> operation)
    {
        while (operation.Status == AsyncStatus.Started) await Task.Delay(5);
        return operation.GetResults();
    }

    private static async Task StreamFrames()
    {
        using (var capture = new MediaCapture())
        {
            await Wait(capture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                StreamingCaptureMode = StreamingCaptureMode.Video,
                SharingMode = MediaCaptureSharingMode.SharedReadOnly,
                MemoryPreference = MediaCaptureMemoryPreference.Cpu
            }));
            var source = capture.FrameSources.Values
                .Where(item => item.Info.SourceKind == MediaFrameSourceKind.Color)
                .OrderByDescending(item => item.Info.MediaStreamType == MediaStreamType.VideoPreview)
                .FirstOrDefault();
            if (source == null) throw new IOException("No color camera available.");
            using (var reader = await Result(capture.CreateFrameReaderAsync(source, "BGRA8")))
            using (var output = new BinaryWriter(Console.OpenStandardOutput()))
            {
                if (await Result(reader.StartAsync()) != MediaFrameReaderStartStatus.Success)
                    throw new IOException("Could not start camera.");
                while (true)
                {
                    using (var frame = reader.TryAcquireLatestFrame())
                    {
                        if (frame != null)
                        {
                            using (var bitmap = frame.VideoMediaFrame.SoftwareBitmap)
                            using (var jpeg = new InMemoryRandomAccessStream())
                            {
                                if (bitmap == null) throw new IOException("No camera bitmap.");
                                var encoder = await Result(BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, jpeg));
                                encoder.SetSoftwareBitmap(bitmap);
                                encoder.BitmapTransform.ScaledWidth = 640;
                                encoder.BitmapTransform.ScaledHeight =
                                    (uint)Math.Max(1, Math.Round(640.0 * bitmap.PixelHeight / bitmap.PixelWidth));
                                await Wait(encoder.FlushAsync());
                                jpeg.Seek(0);
                                using (var data = new DataReader(jpeg.GetInputStreamAt(0)))
                                {
                                    var bytes = new byte[checked((int)jpeg.Size)];
                                    if (await Result(data.LoadAsync((uint)bytes.Length)) != bytes.Length)
                                        throw new IOException("Incomplete camera frame.");
                                    data.ReadBytes(bytes);
                                    output.Write(bytes.Length);
                                    output.Write(bytes);
                                    output.Flush();
                                }
                            }
                        }
                    }
                    await Task.Delay(65);
                }
            }
        }
    }
}
