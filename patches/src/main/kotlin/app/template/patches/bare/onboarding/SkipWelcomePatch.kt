package app.template.patches.bare.onboarding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.template.patches.bare.bareCompatibility
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * ChromeTabbedActivity's post-inflation step. It shows Bare's welcome flow (Welcome to
 * Bare, Make Bare yours, search engine, ad blocker, address bar, updates) unless the
 * "bare_welcome" prefs record this install, then continues normal toolbar setup.
 */
internal object BareWelcomeFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(string("bare_welcome"), string("shown_for_install")),
    // Bare 2.0.4 also writes these prefs from the backup-restore page, which runs the
    // same first-install bookkeeping. Only the startup path reads them back, so require
    // the per-install read and never patch the writer.
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            val reference = (it as? ReferenceInstruction)?.reference
            reference is MethodReference &&
                reference.definingClass == "Landroid/content/SharedPreferences;" &&
                reference.name == "getLong"
        } == true
    },
)

@Suppress("unused")
val skipBareWelcomePatch = bytecodePatch(
    name = "Skip Bare welcome screens",
    description = "Skips the welcome and setup pages at startup.",
    default = true,
) {
    compatibleWith(bareCompatibility())

    execute {
        val method = BareWelcomeFingerprint.match().method
        val instructions = method.implementation!!.instructions.toList()
        fun stringIndex(value: String) = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
        }.takeIf { it >= 0 } ?: throw PatchException("Bare welcome string not found: $value")

        val welcomeIndex = stringIndex("bare_welcome")
        val installIndex = stringIndex("shown_for_install")
        // Bare leaves the flow with the first goto after its per-install check; that
        // destination is the normal startup path, so jump there before reading the prefs.
        // 2.0.4 moved the welcome UI into a helper and left short branches (plain
        // `goto` back into the toolbar setup) between the prefs read and that exit, so
        // take the first branching instruction that really leaves the welcome region.
        val exitIndex = (installIndex + 1 until instructions.size).firstOrNull { index ->
            if (instructions[index].opcode !in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)) return@firstOrNull false
            val target = method.getInstruction<BuilderOffsetInstruction>(index).target.location.instruction
                ?: return@firstOrNull false
            instructions.indexOf(target) > installIndex + 32
        } ?: throw PatchException("Bare welcome exit branch not found")
        val exitTarget = method.getInstruction<BuilderOffsetInstruction>(exitIndex).target.location.instruction
            ?: throw PatchException("Bare welcome exit target not resolved")
        val exitTargetIndex = instructions.indexOf(exitTarget)
        if (exitTargetIndex <= installIndex + 32 || stringIndex("bare_welcome") > exitTargetIndex) {
            throw PatchException("Unexpected Bare welcome flow shape: exit=$exitTargetIndex")
        }

        // Insert after the "bare_welcome" load: the isFinishing() branch targets that
        // instruction, so a jump placed before it would be bypassed.
        method.addInstructionsWithLabels(
            welcomeIndex + 1,
            "goto :skip_bare_welcome",
            ExternalLabel("skip_bare_welcome", exitTarget),
        )
    }
}
