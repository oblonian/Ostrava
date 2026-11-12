import PhotosUI
import SwiftData
import SwiftUI
import UIKit

/// Builds a shareable image of an activity: pick a style, an optional background
/// photo, the format and which stats to show, with a live preview.
struct ShareStudioView: View {
    let activity: Activity

    @Environment(\.modelContext) private var context
    @AppStorage(SettingsKeys.imperialUnits) private var imperial = false

    @State private var spec = ShareSpec()
    @State private var content: ShareContent?
    @State private var photo: UIImage?
    /// Bumped whenever `photo` changes so the preview task re-runs (UIImage isn't Equatable-friendly).
    @State private var photoVersion = 0
    @State private var pickerItem: PhotosPickerItem?
    @State private var preview: UIImage?
    @State private var showCamera = false
    @State private var shareFile: ShareFile?

    private struct ShareFile: Identifiable {
        let url: URL
        var id: URL { url }
    }

    private struct RenderKey: Equatable {
        var spec: ShareSpec
        var photoVersion: Int
        var hasContent: Bool
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                previewImage
                styleSection
                if spec.template == .photo { photoSection }
                if spec.template == .route { themeSection }
                formatSection
                statsSection
                togglesSection
            }
            .padding()
        }
        .navigationTitle("Share activity")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            Button {
                export()
            } label: {
                Label("Share", systemImage: "square.and.arrow.up")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .disabled(content == nil)
            .padding()
            .background(.bar)
        }
        .task { buildContent() }
        .task(id: RenderKey(spec: spec, photoVersion: photoVersion, hasContent: content != nil)) {
            guard let content else { return }
            preview = ShareCardRenderer.render(spec: spec, content: content, photo: photo, scale: 0.5)
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    setPhoto(image)
                }
            }
        }
        .sheet(isPresented: $showCamera) {
            CameraPicker { image in
                setPhoto(image)
            }
            .ignoresSafeArea()
        }
        .sheet(item: $shareFile) { file in
            ActivityView(items: [file.url])
        }
    }

    // MARK: - Sections

    private var previewImage: some View {
        Group {
            if let preview {
                Image(uiImage: preview)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .accessibilityLabel("Preview of the image that will be shared")
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 400)
    }

    private var styleSection: some View {
        section("Style") {
            Picker("Style", selection: $spec.template) {
                ForEach(ShareTemplate.allCases) { template in
                    Text(template.label).tag(template)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var photoSection: some View {
        section("Background photo") {
            HStack {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label(photo == nil ? "Choose photo" : "Change", systemImage: "photo.on.rectangle")
                }
                .buttonStyle(.bordered)
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button {
                        showCamera = true
                    } label: {
                        Label("Camera", systemImage: "camera")
                    }
                    .buttonStyle(.bordered)
                }
                if photo != nil {
                    Button("Remove") {
                        photo = nil
                        pickerItem = nil
                        photoVersion += 1
                    }
                }
            }
            if photo != nil {
                Text("Darken photo").font(.subheadline)
                Slider(value: $spec.photoDim, in: 0...0.7)
            } else {
                Text("Pick a photo from your run to put behind the stats.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var themeSection: some View {
        section("Colour") {
            HStack {
                ForEach(ShareTheme.allCases) { theme in
                    chip(theme.label, selected: spec.theme == theme) { spec.theme = theme }
                }
            }
        }
    }

    private var formatSection: some View {
        section("Format") {
            Picker("Format", selection: $spec.aspect) {
                ForEach(ShareAspect.allCases) { aspect in
                    Text(aspect.label).tag(aspect)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var statsSection: some View {
        section("Stats (up to \(ShareCardRenderer.maxStats))") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(content?.stats.map(\.stat) ?? []) { stat in
                        chip(stat.label, selected: spec.stats.contains(stat)) { toggle(stat) }
                    }
                }
            }
        }
    }

    private var togglesSection: some View {
        VStack {
            Toggle("Show route", isOn: $spec.showRoute)
            if content?.prText != nil {
                Toggle("Show personal record badge", isOn: $spec.showPrBadge)
            }
        }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline.weight(.semibold))
            content()
        }
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(.bordered)
            .tint(selected ? .accentColor : .secondary)
    }

    // MARK: - Behaviour

    private func toggle(_ stat: ShareStat) {
        if let index = spec.stats.firstIndex(of: stat) {
            spec.stats.remove(at: index)
        } else if spec.stats.count < ShareCardRenderer.maxStats {
            spec.stats = (spec.stats + [stat]).sorted { $0.rawValue < $1.rawValue }
        }
    }

    private func setPhoto(_ image: UIImage) {
        photo = downscaled(image)
        photoVersion += 1
        spec.template = .photo
    }

    /// Keeps the working copy of the photo to a sane size and bakes in its orientation.
    private func downscaled(_ image: UIImage, maxDimension: CGFloat = 2160) -> UIImage {
        let largest = max(image.size.width, image.size.height)
        let scale = largest > maxDimension ? maxDimension / largest : 1
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private func buildContent() {
        let type = activity.type
        var stats = [
            ShareStatValue(stat: .distance, value: formatDistance(activity.distance, imperial: imperial), label: "Distance"),
            ShareStatValue(stat: .time, value: formatDuration(activity.movingTime), label: "Time"),
            type.usesPace
                ? ShareStatValue(stat: .pace, value: formatPace(speedMps: activity.avgSpeed, imperial: imperial), label: "Pace")
                : ShareStatValue(stat: .pace, value: formatSpeed(activity.avgSpeed, imperial: imperial), label: "Avg speed"),
            ShareStatValue(stat: .climb, value: formatElevation(activity.elevationGain, imperial: imperial), label: "Elev gain"),
            ShareStatValue(stat: .calories, value: "\(activity.calories) kcal", label: "Calories"),
        ]
        if let heartRate = activity.avgHeartRate {
            stats.append(ShareStatValue(stat: .heartRate, value: "\(heartRate) bpm", label: "Avg HR"))
        }
        if activity.maxSpeed > 0 {
            stats.append(ShareStatValue(stat: .maxSpeed, value: formatSpeed(activity.maxSpeed, imperial: imperial), label: "Max speed"))
        }

        let records = PersonalRecords.rankedEfforts(for: activity, in: context).filter { $0.rank == 1 }
        let prText: String?
        switch records.count {
        case 0: prText = nil
        case 1: prText = "🏆 PR · \(records[0].label)"
        default: prText = "🏆 \(records.count) PRs · \(records.map(\.label).joined(separator: ", "))"
        }

        content = ShareContent(
            title: activity.title,
            subtitle: "\(type.label) · \(formatDate(activity.startTime))",
            stats: stats,
            // The card only needs the route's shape, not every GPS fix.
            points: downsampled(activity.points, to: 800),
            prText: prText
        )
    }

    /// Renders at full resolution, writes a temp file and opens the system share sheet
    /// (which also offers "Save Image").
    private func export() {
        guard let content else { return }
        let image = ShareCardRenderer.render(spec: spec, content: content, photo: photo)
        let photographic = spec.template == .photo && photo != nil
        // Photos compress far better as JPEG; flat gradients stay crisp as PNG.
        let data = photographic ? image.jpegData(compressionQuality: 0.93) : image.pngData()
        guard let data else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(
            "ostrava-\(activity.uid.uuidString.prefix(8)).\(photographic ? "jpg" : "png")"
        )
        do {
            try data.write(to: url)
            shareFile = ShareFile(url: url)
        } catch {
            shareFile = nil
        }
    }
}

/// System share sheet.
private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Camera capture via UIImagePickerController.
private struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void

    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let parent: CameraPicker

        init(_ parent: CameraPicker) {
            self.parent = parent
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage {
                parent.onImage(image)
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
