import SwiftUI
import Shared

/// "New progression" template picker, and named save/load — iOS counterparts of the
/// New/Load/Save entries in Android's ProgressionActivity popup menu. Export/import/share/MIDI
/// and Settings stay Android-only for now.

// MARK: - New progression: template picker

struct NewProgressionTemplateSheet: View {
    @ObservedObject var model: ProgressionModel
    @Environment(\.dismiss) private var dismiss

    /// 0 = "Leer" (no template), i+1 = model.allTemplates[i]. Avoids relying on Kotlin/Native
    /// object equality to track the selected row.
    @State private var selectedIndex: Int?
    @State private var key: Key
    @State private var tempo: Int

    init(model: ProgressionModel) {
        self.model = model
        _key = State(initialValue: model.key)
        _tempo = State(initialValue: model.tempo)
    }

    private var selectedTemplate: ProgressionTemplate? {
        guard let i = selectedIndex, i > 0, i - 1 < model.allTemplates.count else { return nil }
        return model.allTemplates[i - 1]
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                controls
                Divider()
                List {
                    templateRow(index: 0, label: String(localized: "Empty"), template: nil)
                    ForEach(Array(model.allTemplates.enumerated()), id: \.offset) { i, template in
                        templateRow(index: i + 1, label: template.name, template: template)
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle("New Progression")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.stopTemplatePreview(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        model.stopTemplatePreview()
                        model.confirmNewProgression(template: selectedTemplate, key: key, tempo: tempo)
                        dismiss()
                    }
                    .disabled(selectedIndex == nil)
                }
            }
            .onDisappear { model.stopTemplatePreview() }
        }
    }

    private var controls: some View {
        HStack(spacing: 16) {
            Menu {
                ForEach(model.allKeys, id: \.ordinal) { k in
                    Button(k.displayName) {
                        key = k
                        if selectedIndex != nil { model.previewTemplate(selectedTemplate, key: key, tempo: tempo) }
                    }
                }
            } label: {
                Label(key.displayName, systemImage: "key")
                    .font(.subheadline)
            }

            Spacer()

            HStack(spacing: 6) {
                Button { tempo = max(60, tempo - 1) } label: { Image(systemName: "minus.circle") }
                Text(verbatim: "\(tempo)")
                    .font(.subheadline.monospacedDigit())
                    .frame(minWidth: 36)
                Button { tempo = min(240, tempo + 1) } label: { Image(systemName: "plus.circle") }
            }

            if selectedIndex != nil {
                Button {
                    if model.isTemplatePreviewPlaying {
                        model.stopTemplatePreview()
                    } else {
                        model.previewTemplate(selectedTemplate, key: key, tempo: tempo)
                    }
                } label: {
                    Image(systemName: model.isTemplatePreviewPlaying ? "stop.fill" : "play.fill")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func templateRow(index: Int, label: String, template: ProgressionTemplate?) -> some View {
        let isSelected = selectedIndex == index
        return Button {
            selectedIndex = index
            model.previewTemplate(template, key: key, tempo: tempo)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.subheadline.weight(.medium))
                if let description = IosModelBridgeKt.templateDescription(template: template, key: key) {
                    Text(description).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .foregroundStyle(.primary)
        .listRowBackground(isSelected ? Color.accentColor.opacity(0.15) : Color.clear)
    }
}

// MARK: - Load

struct LoadProgressionSheet: View {
    @ObservedObject var model: ProgressionModel
    @Environment(\.dismiss) private var dismiss
    @State private var names: [String] = []

    var body: some View {
        NavigationView {
            Group {
                if names.isEmpty {
                    emptyState
                } else {
                    List {
                        ForEach(names, id: \.self) { name in
                            Button {
                                model.loadProgression(name)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(name).foregroundStyle(.primary)
                                    if let preview = model.progressionPreview(name) {
                                        Text(preview).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        .onDelete(perform: delete)
                    }
                }
            }
            .navigationTitle("Load")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    // Always present (conditional ToolbarContent needs iOS 16); harmless with an
                    // empty list since there's nothing to edit.
                    EditButton().disabled(names.isEmpty)
                }
            }
            .onAppear { names = model.savedProgressionNames() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "tray").font(.largeTitle).foregroundStyle(.secondary)
            Text("No Saved Progressions").foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func delete(at offsets: IndexSet) {
        for index in offsets { model.deleteProgression(names[index]) }
        names = model.savedProgressionNames()
    }
}

// MARK: - Save

struct SaveProgressionSheet: View {
    @ObservedObject var model: ProgressionModel
    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var names: [String] = []
    @State private var showOverwriteConfirm = false

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                TextField("Name", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .padding(16)

                if !names.isEmpty {
                    List {
                        Section("Existing Progressions") {
                            ForEach(names, id: \.self) { existing in
                                Button(existing) { name = existing }
                                    .foregroundStyle(.primary)
                            }
                        }
                    }
                    .listStyle(.plain)
                } else {
                    Spacer()
                }
            }
            .navigationTitle("Save")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { attemptSave() }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { names = model.savedProgressionNames() }
            .alert("Overwrite?", isPresented: $showOverwriteConfirm) {
                Button("Overwrite", role: .destructive) {
                    model.saveNamedProgression(name)
                    dismiss()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("“") + Text(verbatim: name) + Text("” already exists and will be overwritten.")
            }
        }
    }

    private func attemptSave() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        name = trimmed
        if names.contains(trimmed) {
            showOverwriteConfirm = true
        } else {
            model.saveNamedProgression(trimmed)
            dismiss()
        }
    }
}
