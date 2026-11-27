import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.kotlin.library.abi.AbiReadingFilter;
import org.jetbrains.kotlin.library.abi.AbiRenderingSettings;
import org.jetbrains.kotlin.library.abi.AbiSignatureVersion;
import org.jetbrains.kotlin.library.abi.LibraryAbiReader;
import org.jetbrains.kotlin.library.abi.LibraryAbiRenderer;

class DumpClientOperationsAbi {
    public static void main(String[] args) throws Exception {
        var library = LibraryAbiReader.INSTANCE.readAbiInfo(new File(args[0]), new AbiReadingFilter[0]);
        var settings = new AbiRenderingSettings(
            AbiSignatureVersion.Companion.resolveByVersionNumber(2), false, true, "    ",
            (declaration, version) -> { throw new IllegalStateException("Missing ABI signature: " + declaration.getQualifiedName()); }
        );
        var output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, LibraryAbiRenderer.INSTANCE.render(library, settings));
    }
}
