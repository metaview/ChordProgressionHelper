import SwiftUI

struct InfoScreen: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section("App") {
                    HStack {
                        Text("Name")
                        Spacer()
                        Text("Chord Progression Helper")
                            .foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("Version")
                        Spacer()
                        Text(appVersion)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    HStack {
                        Text("Build")
                        Spacer()
                        Text(buildNumber)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                }

                Section("About") {
                    Text("A chord progression editor with live audio preview, drum patterns, strumming patterns, and solo keyboard recording.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Section("License") {
                    Text("© 2024 Metaview Software. All rights reserved.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
    }

    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "Unknown"
    }
}

#Preview {
    InfoScreen()
}
