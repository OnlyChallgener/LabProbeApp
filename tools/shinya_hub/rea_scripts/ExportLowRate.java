import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

// Offline supplementary analysis, outside the APP's compiled source list.
public class ExportLowRate extends GhidraScript {
    public void run() throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(
                Paths.get(getScriptArgs()[0]), StandardCharsets.UTF_8))) {
            output.println("Program: " + currentProgram.getName());
            output.println("SHA256: " + currentProgram.getExecutableSHA256());
            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext() && !monitor.isCancelled()) {
                Function function = functions.next();
                if (function.isExternal() || function.isThunk()) continue;
                output.println("\nFUNCTION " + function.getEntryPoint() + " " + function.getName());
                DecompileResults result = decompiler.decompileFunction(function, 20, monitor);
                if (result.decompileCompleted()) output.println(result.getDecompiledFunction().getC());
                else output.println("UNAVAILABLE: " + result.getErrorMessage());
                output.flush();
            }
        } finally { decompiler.dispose(); }
    }
}
