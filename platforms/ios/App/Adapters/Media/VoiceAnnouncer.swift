import AVFoundation
import Foundation
import MotoNavigationCore

/// Speaks navigation prompts from the shared NavCore snapshot stream.
/// Runs on the phone so the rider can keep earphones connected to the iPhone
/// and mix spoken prompts with music (`duckOthers` lowers music while the
/// announcement plays, then lets it return).
@MainActor
final class VoiceAnnouncer {
    private let synthesizer = AVSpeechSynthesizer()
    private var lastStateName: String?
    private var lastAnnouncedManeuverID: UInt32?
    private var announcedNearManeuverID: UInt32?
    private var sessionConfigured = false

    /// Distance to the upcoming turn at which a second reminder is spoken.
    private static let nearThresholdM: Double = 80

    func observe(_ snapshot: MotoNavCoreSnapshot) {
        let state = snapshot.stateName
        defer { lastStateName = state }

        switch state {
        case "rerouting":
            if lastStateName != "rerouting" {
                resetManeuverTracking()
                speak("偏离路线，正在重新规划")
            }
            return
        case "arrived":
            if lastStateName != "arrived" {
                speak("已到达目的地")
            }
            resetManeuverTracking()
            return
        case "navigating":
            if lastStateName != state {
                resetManeuverTracking()
                speak("开始导航")
                // The first maneuver snapshot usually arrives in the same
                // beat; fall through so it is announced immediately.
            }
        default:
            resetManeuverTracking()
            return
        }

        guard snapshot.hasNextManeuver else { return }
        let maneuverID = snapshot.maneuverID

        if maneuverID != lastAnnouncedManeuverID {
            lastAnnouncedManeuverID = maneuverID
            announcedNearManeuverID = nil
            speak(
                "前方\(Self.distanceText(snapshot.distanceToManeuverM))，\(Self.promptText(snapshot))"
            )
            return
        }

        if announcedNearManeuverID != maneuverID,
           snapshot.distanceToManeuverM <= Self.nearThresholdM
        {
            announcedNearManeuverID = maneuverID
            speak("还有\(Self.distanceText(snapshot.distanceToManeuverM))，\(Self.promptText(snapshot))")
        }
    }

    func reset() {
        resetManeuverTracking()
        lastStateName = nil
        synthesizer.stopSpeaking(at: .immediate)
    }

    private func resetManeuverTracking() {
        lastAnnouncedManeuverID = nil
        announcedNearManeuverID = nil
    }

    private func promptText(_ snapshot: MotoNavCoreSnapshot) -> String {
        if !snapshot.instructionText.isEmpty {
            return snapshot.instructionText
        }
        let phrase = Self.phrase(forManeuverTypeName: snapshot.maneuverTypeName)
        if !snapshot.roadName.isEmpty {
            return "\(phrase)进入\(snapshot.roadName)"
        }
        return phrase
    }

    private static func distanceText(_ meters: Double) -> String {
        guard meters.isFinite, meters > 0 else { return "下一个路口" }
        return meters >= 1_000
            ? String(format: "%.1f公里", meters / 1_000)
            : "\(Int(meters.rounded()))米"
    }

    private static func phrase(forManeuverTypeName typeName: String) -> String {
        switch typeName {
        case "continue": "直行"
        case "slight_left": "向左前方行驶"
        case "left": "左转"
        case "sharp_left": "向左后方急转"
        case "u_turn_left": "掉头"
        case "slight_right": "向右前方行驶"
        case "right": "右转"
        case "sharp_right": "向右后方急转"
        case "u_turn_right": "掉头"
        case "roundabout": "进入环岛"
        case "exit": "驶出环岛"
        case "arrive": "到达目的地"
        default: "继续行驶"
        }
    }

    private func speak(_ text: String) {
        guard !text.isEmpty else { return }
        if !sessionConfigured {
            try? AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .spokenAudio,
                options: [.duckOthers]
            )
            sessionConfigured = true
        }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        synthesizer.speak(utterance)
    }
}
