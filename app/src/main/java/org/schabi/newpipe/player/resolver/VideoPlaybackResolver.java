package org.schabi.newpipe.player.resolver;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.android.exoplayer2.C.TIME_UNSET;
import static org.schabi.newpipe.util.ListHelper.*;


import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.io.InputStream;
import javax.xml.parsers.ParserConfigurationException;
import java.net.MalformedURLException;
import org.xml.sax.SAXException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Base64;

import org.schabi.newpipe.extractor.utils.LogUtil;

public class VideoPlaybackResolver implements PlaybackResolver {
    private static final String TAG = VideoPlaybackResolver.class.getSimpleName();

    @NonNull
    private final Context context;
    @NonNull
    private final PlayerDataSource dataSource;
    @NonNull
    private final QualityResolver qualityResolver;
    private SourceType streamSourceType;

    private int selectedIndex = -1;

    private List<String> blacklistUrls = new ArrayList<>();

    public enum SourceType {
        LIVE_STREAM,
        VIDEO_WITH_SEPARATED_AUDIO,
        VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
    }

    public VideoPlaybackResolver(@NonNull final Context context,
                                 @NonNull final PlayerDataSource dataSource,
                                 @NonNull final QualityResolver qualityResolver) {
        this.context = context;
        this.dataSource = dataSource;
        this.qualityResolver = qualityResolver;
    }

    @Override
    @Nullable
    public MediaSource resolve(@NonNull final StreamInfo info) {
        final MediaSource liveSource = PlaybackResolver.maybeBuildLiveMediaSource(dataSource, info);
        String logMessage = "liveSource=" + liveSource;
        LogUtil.logWithMessage("tree-test02", logMessage);
        if (liveSource != null) {
            streamSourceType = SourceType.LIVE_STREAM;
            return liveSource;
        }

        final List<MediaSource> mediaSources = new ArrayList<>();
        final List<VideoStream> videoStreams = new ArrayList<>(info.getVideoStreams());
        final List<VideoStream> videoOnlyStreams = new ArrayList<>(info.getVideoOnlyStreams());

        removeTorrentStreams(videoStreams);
        removeTorrentStreams(videoOnlyStreams);

        // Create video stream source
        final List<VideoStream> videos = ListHelper.getSortedStreamVideosList(context,
                videoStreams, videoOnlyStreams, false, true)
                .stream().filter(s -> !blacklistUrls.contains(s.getContent())).collect(Collectors.toList());
        final int index;
        if (videos.isEmpty()) {
            index = -1;
        } else if (selectedIndex == -1) {
            index = qualityResolver.getDefaultResolutionIndex(videos);
        } else {
            index = qualityResolver.getOverrideResolutionIndex(videos, selectedIndex);
        }
        final MediaItemTag tag = StreamInfoTag.of(info, videos, index);
        @Nullable final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream)
                .orElse(null);

        if (video != null) {
            try {
                final MediaSource streamSource = PlaybackResolver.buildMediaSource(
                        dataSource, video, info, PlayerHelper.cacheKeyOf(info, video), tag);
                mediaSources.add(streamSource);
            } catch (final IOException e) {
                Log.e(TAG, "Unable to create video source:", e);
                return null;
            }
        }

        // Create optional audio stream source
        List<AudioStream> audioStreams = info.getAudioStreams()
                .stream().filter(s -> !blacklistUrls.contains(s.getContent())).collect(Collectors.toList());
        removeTorrentStreams(audioStreams);
        audioStreams = filterUnsupportedFormats(audioStreams, context);
        final AudioStream audio = audioStreams.isEmpty() ? null : audioStreams.get(
                ListHelper.getDefaultAudioFormat(context, audioStreams));

        // Use the audio stream if there is no video stream, or
        // merge with audio stream in case if video does not contain audio
        if (audio != null && (video == null || video.isVideoOnly())) {
            try {
                final MediaSource audioSource = PlaybackResolver.buildMediaSource(
                        dataSource, audio, info, PlayerHelper.cacheKeyOf(info, audio), tag);
                mediaSources.add(audioSource);
                streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO;
            } catch (final IOException e) {
                Log.e(TAG, "Unable to create audio source:", e);
                return null;
            }
        } else {
            streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
        }

        // If there is no audio or video sources, then this media source cannot be played back
        if (mediaSources.isEmpty()) {
            return null;
        }
        // Below are auxiliary media sources

        // Create subtitle sources
        final List<SubtitlesStream> subtitlesStreams = info.getSubtitles();
        if (subtitlesStreams != null) {
            // 去重：保存每个字幕的 (start + content) 的组合
            Set<String> seenSubtitles = new HashSet<>();

            // Torrent and non URL subtitles are not supported by ExoPlayer
            final List<SubtitlesStream> nonTorrentAndUrlStreams = removeNonUrlAndTorrentStreams(
                    subtitlesStreams);
            for (final SubtitlesStream subtitle : nonTorrentAndUrlStreams) {
                //String subtitleUrl = subtitle.getContent() + "&fmt=srv3";
                String subtitleUrl = subtitle.getContent();
                LogUtil.logWithMessage("tree-test02", subtitleUrl);
                Optional<String> subtitleContent = parseAndDeduplicateSubtitle(subtitleUrl, seenSubtitles);
                if (!subtitleContent.isPresent()) {
                    continue; // 解析失败，跳过当前字幕
                }

                final MediaFormat mediaFormat = subtitle.getFormat();
                if (mediaFormat != null) {
                    @C.RoleFlags final int textRoleFlag = subtitle.isAutoGenerated()
                            ? C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                            : C.ROLE_FLAG_CAPTION;
                    if(!subtitle.isUrl()){
                        final MediaItem.SubtitleConfiguration textMediaItem =
                                new MediaItem.SubtitleConfiguration.Builder(
                                        Uri.parse(""))
                                        .setMimeType(mediaFormat.getMimeType())
                                        .setRoleFlags(textRoleFlag)
                                        .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                        .build();
                        final MediaSource textSource =
                                new SingleSampleMediaSource.Factory(new CustomDataSourceFactory(context, null, subtitle.getContent().getBytes()))
                                        .createMediaSource(textMediaItem, C.TIME_UNSET);
                        mediaSources.add(textSource);
                        continue;
                    }
                    final MediaItem.SubtitleConfiguration textMediaItem =
                            new MediaItem.SubtitleConfiguration.Builder(
                                    Uri.parse(subtitleContent.get()))
                                    //.setMimeType(mediaFormat.getMimeType())
                                    .setMimeType("application/ttml+xml") // 明确 TTML 格式
                                    .setRoleFlags(textRoleFlag)
                                    .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                    .build();
                    final MediaSource textSource = dataSource.getSingleSampleMediaSourceFactory()
                            .createMediaSource(textMediaItem, TIME_UNSET);
                    mediaSources.add(textSource);
                }
            }
        }

        if (mediaSources.size() == 1) {
            return mediaSources.get(0);
        } else {
            return new MergingMediaSource(true, mediaSources.toArray(new MediaSource[0]));
        }
    }

    /**
     * 解析字幕 XML 并去重，返回去重后的内容或原始 URL。
     * @param subtitleUrl 字幕 URL（需包含 fmt=srv3）
     * @param isUrl 是否为 URL 字幕
     * @return Optional 包含去重后的字幕内容（非 URL）或原始 URL（URL 字幕），失败返回 empty
     */
    private Optional<String> parseAndDeduplicateSubtitle(String subtitleUrl, Set<String> seenSubtitles) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            URL url = new URL(subtitleUrl);
            InputStream inputStream = url.openStream();
            Document doc = factory.newDocumentBuilder().parse(inputStream);
            inputStream.close();
            doc.getDocumentElement().normalize();

            StringBuilder ttmlContent = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">\n" +
                    "<head><metadata><ttm:title>Subtitles</ttm:title></metadata></head>\n<body>\n<div>\n");
            NodeList textNodes = doc.getElementsByTagName("p");
            if (textNodes.getLength() == 0) {
                textNodes = doc.getElementsByTagName("text");
            }
            //LogUtil.logWithMessage("tree-test02", "Subtitle XML has " + textNodes.getLength() + " text nodes (tag: " + (textNodes.getLength() > 0 ? textNodes.item(0).getNodeName() : "none") + ")");
            int deduplicatedCount = 0;
            StringBuilder ttmlPreview = new StringBuilder(); // 预览前几个 <p> 标签
            for (int i = 0; i < textNodes.getLength(); i++) {
                Element textElement = (Element) textNodes.item(i);
                String startTimeSec = textElement.getAttribute("begin");
                if (startTimeSec.isEmpty()) {
                    startTimeSec = textElement.getAttribute("start");
                }
                String duration = textElement.getAttribute("dur");
                String content = textElement.getTextContent().replaceAll("<font[^>]*>", "").replaceAll("</font>", "").trim(); // 移除 <font> 标签
                String uniqueKey = startTimeSec + "|" + duration + "|" + content; // 严谨去重
                if (!seenSubtitles.add(uniqueKey)) {
                    //LogUtil.logWithMessage("tree-test02", "Skipped duplicate subtitle: " + uniqueKey);
                    continue;
                }
                String formattedStart = formatTtmlTime(startTimeSec);
                String formattedDur = formatTtmlTime(duration.isEmpty() ? "1.0" : duration);
                ttmlContent.append("<p begin=\"").append(formattedStart).append("\" dur=\"").append(formattedDur).append("\">")
                           .append(content).append("</p>\n");
                if (deduplicatedCount < 3) { // 记录前 3 个 <p> 标签
                    ttmlPreview.append("<p begin=\"").append(formattedStart).append("\" dur=\"").append(formattedDur).append("\">")
                               .append(content).append("</p>\n");
                }
                deduplicatedCount++;
            }
            ttmlContent.append("</div>\n</body>\n</tt>");
            String result = ttmlContent.toString();
            if (result.length() <= 100) {
                //LogUtil.logWithMessage("tree-test02", "No subtitle content parsed for " + subtitleUrl);
                return Optional.empty();
            }
            //LogUtil.logWithMessage("tree-test02", "Deduplicated " + deduplicatedCount + " subtitles from " + textNodes.getLength());
            //LogUtil.logWithMessage("tree-test02", "TTML preview (first 3 entries):\n" + ttmlPreview.toString());
            String tempUrl = "data:application/ttml+xml;base64," + Base64.getEncoder().encodeToString(result.getBytes("UTF-8"));
            //LogUtil.logWithMessage("tree-test02", "Generated temp URL length: " + tempUrl.length());
            return Optional.of(tempUrl);
        } catch (ParserConfigurationException | IOException | SAXException e) {
            Log.e(TAG, "Failed to parse subtitle: " + subtitleUrl, e);
            return Optional.empty();
        }
    }

    // 修改 formatTtmlTime 方法
    private String formatTtmlTime(String time) {
        try {
            double seconds = Double.parseDouble(time.replace("s", ""));
            int hours = (int) (seconds / 3600);
            seconds %= 3600;
            int minutes = (int) (seconds / 60);
            seconds %= 60;
            int milliseconds = (int) ((seconds - (int) seconds) * 1000);
            return String.format("%02d:%02d:%02d.%03d", hours, minutes, (int) seconds, milliseconds);
        } catch (NumberFormatException e) {
            return "00:00:01.000"; // 默认 1 秒
        }
    }


    /**
     * Returns the last resolved {@link StreamInfo}'s {@link SourceType source type}.
     *
     * @return {@link Optional#empty()} if nothing was resolved, otherwise the {@link SourceType}
     * of the last resolved {@link StreamInfo} inside an {@link Optional}
     */
    public Optional<SourceType> getStreamSourceType() {
        return Optional.ofNullable(streamSourceType);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int selectedIndex) {
        this.selectedIndex = selectedIndex;
    }

    public void addBlacklistUrl(@NonNull final String url) {
        blacklistUrls.add(url);
    }

    public List<String> getBlacklistUrls() {
        return blacklistUrls;
    }
}
