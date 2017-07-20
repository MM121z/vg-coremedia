package com.vg.live.video;

import static com.github.davidmoten.rx.Checked.a1;
import static com.github.davidmoten.rx.Checked.f0;
import static com.github.davidmoten.rx.Checked.f1;
import static com.github.davidmoten.rx.Checked.f2;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.primitives.Ints.checkedCast;
import static com.vg.live.video.AVFrameUtil.annexb2mp4;
import static java.util.Arrays.asList;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.jcodec.codecs.h264.H264Utils.parseAVCC;
import static org.jcodec.codecs.h264.H264Utils.readPPSFromBufferList;
import static org.jcodec.codecs.h264.H264Utils.readSPSFromBufferList;
import static org.jcodec.codecs.mpeg4.mp4.EsdsBox.createEsdsBox;
import static org.jcodec.containers.mp4.boxes.AudioSampleEntry.createAudioSampleEntry;
import static org.jcodec.containers.mp4.muxer.MP4Muxer.createMP4MuxerToChannel;
import static rx.Observable.using;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.PictureParameterSet;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.mp4.AvcCBox;
import org.jcodec.codecs.mpeg4.mp4.EsdsBox;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.containers.mp4.MP4Packet;
import org.jcodec.containers.mp4.TrackType;
import org.jcodec.containers.mp4.boxes.AudioSampleEntry;
import org.jcodec.containers.mp4.boxes.Box;
import org.jcodec.containers.mp4.boxes.Header;
import org.jcodec.containers.mp4.boxes.MovieBox;
import org.jcodec.containers.mp4.boxes.NodeBox;
import org.jcodec.containers.mp4.boxes.SampleEntry;
import org.jcodec.containers.mp4.boxes.SyncSamplesBox;
import org.jcodec.containers.mp4.boxes.TrakBox;
import org.jcodec.containers.mp4.boxes.VideoSampleEntry;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.containers.mp4.muxer.FramesMP4MuxerTrack;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vg.live.worker.Allocator;
import com.vg.live.worker.SimpleAllocator;

import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.observables.SyncOnSubscribe;

public class MP4MuxerUtils {
    private final static Logger log = LoggerFactory.getLogger(MP4MuxerUtils.class);

    public static Observable<File> writemp4(Observable<AVFrame> _frames, Func0<File> nextFile) {
        return writemp4(_frames, nextFile.call());
    }

    public static Observable<File> writemp4(Observable<AVFrame> frames, File file) {
        Observable<File> compose = frames.compose(concatMapOnFirst(allFrames -> {
            Observable<MP4Muxer> _muxer = using(
                    f0(() -> new FileChannelWrapper(new FileOutputStream(file).getChannel())),
                    f1(output -> writeFrames(allFrames, createMP4MuxerToChannel(output))),
                    output -> closeQuietly(output));
            return _muxer.map(m -> file);
        }));
        return compose;
    }

    public static Observable<ByteBuffer> writemp4(Observable<AVFrame> frames, ByteBuffer file) {
        return frames.compose(concatMapOnFirst(allFrames -> {
            return using(
                    f0(() -> new ByteBufferChannel(file)),
                    f1(output -> {
                        Observable<ByteBuffer> bufs = writeFrames(allFrames, createMP4MuxerToChannel(output)).map(x -> output.getContents());
                        return bufs;
                    }),
                    output -> IOUtils.closeQuietly(output));
        }));
    }

    static Observable<MP4Muxer> writeFrames(Observable<AVFrame> frames, MP4Muxer muxer) {
        return writeFrames(frames, muxer, true);
    }

    static Observable<MP4Muxer> writeFrames(Observable<AVFrame> frames, MP4Muxer muxer, boolean releaseFrames) {
        Observable<MutablePair<MP4Muxer, Integer>> reduce = frames.reduce(MutablePair.of(muxer, 0), f2((p, f) -> {
            MP4Muxer m = p.getKey();
            if (f.isVideo()) {
                if (f.isIFrame()) {
                    p.right += 1;
                }
                FramesMP4MuxerTrack vTrack = (FramesMP4MuxerTrack) muxer.getVideoTrack();
                if (vTrack == null) {
                    vTrack = addVideoTrack(m, f);
                }
                vTrack.addFrame(mp4(f));
            } else if (f.isAudio()) {
                FramesMP4MuxerTrack aTrack = (FramesMP4MuxerTrack) firstElement(muxer.getAudioTracks());
                if (aTrack == null) {
                    aTrack = addAudioTrack(m, f);
                }
                aTrack.addFrame(mp4(f));
            }
            if (releaseFrames) {
                //FramePool.releaseData(f);
            }
            return p;
        }));
        return reduce.doOnNext(a1(p -> {
            MP4Muxer m = p.getKey();
            int iframes = p.right;
            if (!m.getTracks().isEmpty()) {
                MovieBox movieBox = muxer.finalizeHeader();
                TrakBox videoTrack = movieBox.getVideoTrack();
                if (videoTrack != null && videoTrack.getStss() == null && videoTrack.getSampleCount() > iframes) {
                    //add empty stss to video track if it does not exist and not all frames are iframes
                    NodeBox stbl = NodeBox.findFirstPath(videoTrack, NodeBox.class, Box.path("mdia.minf.stbl"));
                    stbl.add(SyncSamplesBox.createSyncSamplesBox(new int[0]));
                }
                m.storeHeader(movieBox);
            }
        })).map(p -> p.getKey());
    }

    public static FramesMP4MuxerTrack addVideoTrack(MP4Muxer muxer, AVFrame frame) {
        checkArgument(frame.timescale > 0, "timescale <= 0 in %s", frame);
        FramesMP4MuxerTrack vTrack = muxer.addTrack(TrackType.VIDEO, checkedCast(frame.timescale));
        SampleEntry sampleEntry = videoSampleEntry(frame);
        vTrack.addSampleEntry(sampleEntry);
        return vTrack;
    }

    public static FramesMP4MuxerTrack addAudioTrack(MP4Muxer muxer, AVFrame frame) {
        checkArgument(frame.timescale > 0, "timescale <= 0 in %s", frame);
        FramesMP4MuxerTrack aTrack = muxer.addTrack(TrackType.SOUND, checkedCast(frame.timescale));
        SampleEntry sampleEntry = audioSampleEntry(frame.adtsHeader);
        aTrack.addSampleEntry(sampleEntry);
        return aTrack;
    }

    public static MP4Packet mp4(AVFrame f) {
        checkArgument(f.timescale > 0, "timescale <= 0 in frame %s", f);
        return new MP4Packet(f.data(), f.pts, f.timescale, Math.max(0, f.duration), 0, f.isIFrame(), null, 0, f.pts, 0,
                0, f.data().remaining(), false);
    }

    public static SampleEntry videoSampleEntry(AVFrame videoFrame) {
        checkNotNull(videoFrame.getSps(), "sps not found in videoFrame %s", videoFrame);
        SeqParameterSet sps = videoFrame.getSps();
        int nalLenSize = 4;

        AvcCBox avcC = AvcCBox.createAvcCBox(sps.profile_idc, 0, sps.level_idc, nalLenSize, asList(videoFrame.spsBuf),
                asList(videoFrame.ppsBuf));

        SampleEntry sampleEntry = H264Utils.createMOVSampleEntryFromAvcC(avcC);
        return sampleEntry;
    }

    public static AudioSampleEntry audioSampleEntry(ADTSHeader adts) {
        AudioSampleEntry ase = compressedAudioSampleEntry("mp4a", 1, adts.getChanConfig(), adts.getSampleRate());
        ByteBuffer streamInfo = ADTSHeader.decoderSpecific(adts);
        EsdsBox esds = createEsdsBox(streamInfo, (adts.getObjectType().ordinal()) << 5, 8192, 128 * 1024, 128 * 1024, 1);
        ase.add(esds);
        return ase;
    }

    public static AudioSampleEntry compressedAudioSampleEntry(String fourcc, int drefId, int channels, int sampleRate) {
        return createAudioSampleEntry(Header.createHeader(fourcc, 0), (short) drefId, (short) channels, (short) 16,
                sampleRate, (short) 0, 0, 65534, 0, 0, 0, 0, 16 / 8, (short) 0);
    }

    public static Observable<AVFrame> framesFromMp4(File file) {
        return framesFromMp4(file, SimpleAllocator.DEFAULT_ALLOCATOR);
    }
    
    public static Observable<AVFrame> framesFromMp4(File file, Allocator alloc) {
        Observable<AVFrame> frames = Observable.using(
                f0(() -> new FileChannelWrapper(new FileInputStream(file).getChannel())),
                f1(input -> MP4MuxerUtils.frames(new MP4Demuxer(input), alloc)), input -> {
                    log.debug("close {}", file);
                    IOUtils.closeQuietly(input);
                });
        return frames.map(f2 -> annexb2mp4(f2));
    }

    public static AVFrame frame(MP4Packet pkt, AVFrame _f, Allocator alloc) {
        ByteBuffer data = pkt.getData();
        _f.timescale = pkt.getTimescale();
        _f._data = alloc.copy(data);
        _f.duration = pkt.getDuration();
        _f.pts = pkt.getPts();
        _f.streamOffset = pkt.getFileOff();
        return _f;
    }

    public static Observable<AVFrame> populateSpsPps(VideoSampleEntry vse, Observable<AVFrame> frames) {
        checkNotNull(vse);

        AvcCBox avcc = parseAVCC(vse);
        checkNotNull(avcc);
        checkNotNull(avcc.getSpsList());
        checkNotNull(avcc.getPpsList());
        checkState(!avcc.getSpsList().isEmpty());
        checkState(!avcc.getPpsList().isEmpty());
        checkNotNull(avcc.getSpsList().get(0));
        checkNotNull(avcc.getPpsList().get(0));

        SeqParameterSet sps = readSPSFromBufferList(avcc.getSpsList()).get(0);
        PictureParameterSet pps = readPPSFromBufferList(avcc.getPpsList()).get(0);
        ByteBuffer spsBuf = avcc.getSpsList().get(0);
        ByteBuffer ppsBuf = avcc.getPpsList().get(0);

        return frames.map(frame -> {
            frame.sps = sps;
            frame.pps = pps;
            frame.spsBuf = spsBuf;
            frame.ppsBuf = ppsBuf;
            return frame;
        });
    }

    public static AVFrame populateSpsPps(VideoSampleEntry vse, AVFrame frame) {
        checkNotNull(vse);

        AvcCBox avcc = parseAVCC(vse);
        checkNotNull(avcc);
        checkNotNull(avcc.getSpsList());
        checkNotNull(avcc.getPpsList());
        checkState(!avcc.getSpsList().isEmpty());
        checkState(!avcc.getPpsList().isEmpty());
        checkNotNull(avcc.getSpsList().get(0));
        checkNotNull(avcc.getPpsList().get(0));

        SeqParameterSet sps = readSPSFromBufferList(avcc.getSpsList()).get(0);
        PictureParameterSet pps = readPPSFromBufferList(avcc.getPpsList()).get(0);
        ByteBuffer spsBuf = avcc.getSpsList().get(0);
        ByteBuffer ppsBuf = avcc.getPpsList().get(0);

        frame.sps = sps;
        frame.pps = pps;
        frame.spsBuf = spsBuf;
        frame.ppsBuf = ppsBuf;
        return frame;
    }

    public static Observable<AVFrame> frames(MP4Demuxer demuxer, Allocator alloc) {
        int nextTrackId = getNextTrackId(demuxer);
        ADTSHeader adts[] = new ADTSHeader[nextTrackId];
        VideoSampleEntry vse = videoSampleEntry(demuxer);

        List<AbstractMP4DemuxerTrack> tracks = getAVTracks(demuxer);

        tracks.stream().filter(t -> t.getBox().isAudio()).forEach(t -> {
            AudioSampleEntry ase = (AudioSampleEntry) firstElement(t.getSampleEntries());
            adts[t.getNo()] = ADTSHeader.adtsFromAudioSampleEntry(ase);
        });

        Observable<List<AVFrame>> framesRx = Observable.create(SyncOnSubscribe.createStateful(() -> {
            return new ArrayList<>(tracks);
        }, (_tracks, o) -> {
            if (_tracks.isEmpty()) {
                o.onCompleted();
                return _tracks;
            }
            ListIterator<AbstractMP4DemuxerTrack> it = _tracks.listIterator();
            List<AVFrame> frames = new ArrayList<>(_tracks.size());
            try {
                while (it.hasNext()) {
                    AbstractMP4DemuxerTrack t = it.next();
                    MP4Packet pkt = (MP4Packet) t.nextFrame();
                    if (pkt == null) {
                        it.remove();
                    } else {
                        if (t.getBox().isVideo()) {
                            AVFrame f = AVFrame.video(0, pkt.getData().remaining(), pkt.isKeyFrame());
                            frames.add(frame(pkt, f, alloc));
                        } else if (t.getBox().isAudio()) {
                            AVFrame f = AVFrame.audio(0, pkt.getData().remaining());
                            AVFrame frame = frame(pkt, f, alloc);
                            frame.adtsHeader = adts[t.getNo()];
                            frames.add(frame);
                        }
                    }
                }
            } catch (IOException e) {
                o.onError(e);
                return _tracks;
            }
            if (!frames.isEmpty()) {
                o.onNext(frames);
            } else {
                o.onCompleted();
            }
            return _tracks;
        }));

        return framesRx.concatMapIterable(x -> x).groupBy(f -> f.isVideo()).flatMap(g -> {
            boolean isVideo = g.getKey();
            return isVideo ? populateSpsPps(vse, g) : g;
        });
    }

    private static List<AbstractMP4DemuxerTrack> getAVTracks(MP4Demuxer demuxer) {
        List<AbstractMP4DemuxerTrack> tracks = list(demuxer.getTracks());
        List<AbstractMP4DemuxerTrack> avTracks = new ArrayList<>();
        for (AbstractMP4DemuxerTrack t : tracks) {
            SampleEntry se = firstElement(t.getSampleEntries());
            boolean isAudio = t.getBox().isAudio() && se != null && (se instanceof AudioSampleEntry);
            boolean isVideo = t.getBox().isVideo() && se != null && (se instanceof VideoSampleEntry);
            if (isAudio || isVideo) {
                avTracks.add(t);
            }
        }

        return avTracks;
    }

    private static int getNextTrackId(MP4Demuxer demuxer) {
        if (demuxer == null) return 1;
        AbstractMP4DemuxerTrack[] tracks = demuxer.getTracks();
        if (tracks == null || tracks.length == 0)
            return 1;
        int max = 0;
        for (AbstractMP4DemuxerTrack track : tracks) {
            max = Math.max(max, track.getNo());
        }
        return max + 1;
    }

    private static <T> List<T> list(T[] array) {
        return array != null ? Arrays.asList(array) : Collections.emptyList();
    }

    private static <T> T firstElement(List<T> list) {
        return list != null && !list.isEmpty() ? list.get(0) : null;
        
    }
    private static <T> T firstElement(T... array) {
        return array != null && array.length > 0 ? array[0] : null;
    }

    private static VideoSampleEntry videoSampleEntry(MP4Demuxer demuxer) {
        if (demuxer == null) return null;
        AbstractMP4DemuxerTrack videoTrack = demuxer.getVideoTrack();
        if (videoTrack == null) return null;
        SampleEntry sampleEntry = firstElement(videoTrack.getSampleEntries());
        if (sampleEntry == null) return null;
        if (!(sampleEntry instanceof  VideoSampleEntry)) {
            return null;
        }
        return (VideoSampleEntry) sampleEntry;
    }

    static Observable<MP4Packet> videoPackets(File file) {
        Func1<SeekableByteChannel, Observable<MP4Packet>> observableFactory = f1(input -> {
            MP4Demuxer demuxer = new MP4Demuxer(input);
            return com.vg.live.MP4Helper.packets(demuxer.getVideoTrack());
        });
        return Observable.using(f0(() -> new FileChannelWrapper(new FileInputStream(file).getChannel())),
                observableFactory, input -> closeQuietly(input));
    }

    public static <T, R> Observable.Transformer<T, R> concatMapOnFirst(Func1<Observable<T>, Observable<R>> func) {
        return t -> {
            Observable<T> autoConnect = t.replay(1).autoConnect();
            Observable<T> firstOne = autoConnect.take(1);
            return firstOne.concatMap(first -> func.call(autoConnect));
        };
    }

    public static <T, R> Observable.Transformer<T, R> concatMapOnFirst(Func2<T, Observable<T>, Observable<R>> func) {
        return t -> {
            Observable<T> autoConnect = t.replay(1).autoConnect();
            Observable<T> firstOne = autoConnect.take(1);
            return firstOne.concatMap(first -> func.call(first, autoConnect));
        };
    }

}
