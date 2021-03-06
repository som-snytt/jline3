/*
 * Copyright (c) 2002-2017, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.InputFlag;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.Status;
import org.jline.utils.InfoCmp.Capability;
import org.mozilla.universalchardet.UniversalDetector;

import static org.jline.keymap.KeyMap.KEYMAP_LENGTH;
import static org.jline.keymap.KeyMap.alt;
import static org.jline.keymap.KeyMap.ctrl;
import static org.jline.keymap.KeyMap.del;
import static org.jline.keymap.KeyMap.key;

public class Nano {

    // Final fields
    protected final Terminal terminal;
    protected final Display display;
    protected final BindingReader bindingReader;
    protected final Size size;
    protected final Path root;
    protected final boolean restricted;
    protected final int vsusp;

    // Keys
    protected KeyMap<Operation> keys;

    // Configuration
    public String title = "JLine Nano 3.0.0";
    public boolean printLineNumbers = true;
    public boolean wrapping = true;
    public boolean smoothScrolling = true;
    public boolean mouseSupport = false;
    public boolean oneMoreLine = true;
    public boolean constantCursor;
    public int tabs = 1;   // tabs are not currently supported!
    public String brackets = "\"’)>]}";
    public String matchBrackets = "(<[{)>]}";
    public String punct = "!.?";
    public String quoteStr = "^([ \\t]*[#:>\\|}])+";

    // Input
    protected final List<Buffer> buffers = new ArrayList<>();
    protected int bufferIndex;
    protected Buffer buffer;

    protected String message;
    protected int nbBindings = 0;

    protected LinkedHashMap<String, String> shortcuts;

    protected String editMessage;
    protected final StringBuilder editBuffer = new StringBuilder();

    protected boolean searchCaseSensitive;
    protected boolean searchRegexp;
    protected boolean searchBackwards;
    protected String searchTerm;
    protected List<String> searchTerms = new ArrayList<>();
    protected int searchTermId = -1;
    protected WriteMode writeMode = WriteMode.WRITE;
    protected boolean writeBackup;
    protected List<String> cutbuffer = new ArrayList<>();
    protected boolean cut2end = false;
    protected boolean mark = false;

    protected boolean readNewBuffer = true;

    protected enum WriteMode {
        WRITE,
        APPEND,
        PREPEND
    }

    protected enum WriteFormat {
        UNIX,
        DOS,
        MAC
    }

    protected class Buffer {
        String file;
        Charset charset;
        WriteFormat format = WriteFormat.UNIX;
        List<String> lines;

        int firstLineToDisplay;
        int firstColumnToDisplay = 0;
        int offsetInLineToDisplay;

        int line;
        List<LinkedList<Integer>> offsets = new ArrayList<>();
        int offsetInLine;
        int column;
        int wantedColumn;
        boolean uncut = false;
        int[] markPos = {-1, -1}; // line, offsetInLine + column
        SyntaxHighlighter syntaxHighlighter;

        boolean dirty;

        protected Buffer(String file) {
            this.file = file;
            this.syntaxHighlighter = doSyntaxHighlighter();
        }

        void open() throws IOException {
            if (lines != null) {
                return;
            }

            lines = new ArrayList<>();
            lines.add("");
            charset = Charset.defaultCharset();
            computeAllOffsets();

            if (file == null) {
                return;
            }

            Path path = root.resolve(file);
            if (Files.isDirectory(path)) {
                setMessage("\"" + file + "\" is a directory");
                return;
            }

            try (InputStream fis = Files.newInputStream(path))
            {
                read(fis);
            } catch (IOException e) {
                setMessage("Error reading " + file + ": " + e.getMessage());
            }
        }

        void open(InputStream is) throws IOException {
            if (lines != null) {
                return;
            }

            lines = new ArrayList<>();
            lines.add("");
            charset = Charset.defaultCharset();
            computeAllOffsets();

            read(is);
        }

        void read(InputStream fis) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int remaining;
            while ((remaining = fis.read(buffer)) > 0) {
                bos.write(buffer, 0, remaining);
            }
            byte[] bytes = bos.toByteArray();

            try {
                UniversalDetector detector = new UniversalDetector(null);
                detector.handleData(bytes, 0, bytes.length);
                detector.dataEnd();
                if (detector.getDetectedCharset() != null) {
                    charset = Charset.forName(detector.getDetectedCharset());
                }
            } catch (Throwable t) {
                // Ignore
            }

            // TODO: detect format, do not eat last newline
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
                String line;
                lines.clear();
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (lines.isEmpty()) {
                lines.add("");
            }
            computeAllOffsets();
            moveToChar(0);
        }

        void insert(String insert) {
            String text = lines.get(line);
            int pos = offsetInLine + column;
            insert = insert.replaceAll("\r\n", "\n");
            insert = insert.replaceAll("\r", "\n");
            String mod;
            if (pos == text.length()) {
                mod = text + insert;
            } else {
                mod = text.substring(0, pos) + insert + text.substring(pos);
            }
            List<String> ins = new ArrayList<>();
            int last = 0;
            int idx = mod.indexOf('\n', last);
            while (idx >= 0) {
                ins.add(mod.substring(last, idx));
                last = idx + 1;
                idx = mod.indexOf('\n', last);
            }
            ins.add(mod.substring(last));
            lines.set(line, ins.get(0));
            offsets.set(line, computeOffsets(ins.get(0)));
            for (int i = 1; i < ins.size(); i++) {
                ++line;
                lines.add(line, ins.get(i));
                offsets.add(line, computeOffsets(ins.get(i)));
            }
            moveToChar(ins.get(ins.size() - 1).length() - (text.length() - pos));
            ensureCursorVisible();
            dirty = true;
        }

        void computeAllOffsets() {
            offsets.clear();
            for (String text : lines) {
                offsets.add(computeOffsets(text));
            }
        }

        LinkedList<Integer> computeOffsets(String text) {
            int width = size.getColumns() - (printLineNumbers ? 8 : 0);
            LinkedList<Integer> offsets = new LinkedList<>();
            offsets.add(0);
            if (wrapping) {
                int last = 0;
                int prevword = 0;
                boolean inspace = false;
                for (int i = 0; i < text.length(); i++) {
                    if (isBreakable(text.charAt(i))) {
                        inspace = true;
                    } else if (inspace) {
                        prevword = i;
                        inspace = false;
                    }
                    if (i == last + width - 1) {
                        if (prevword == last) {
                            prevword = i;
                        }
                        offsets.add(prevword);
                        last = prevword;
                    }
                }
            }
            return offsets;
        }

        boolean isBreakable(char ch) {
            return ch == ' ';
        }

        void moveToChar(int pos) {
            if (!wrapping) {
                if (pos > column && pos - firstColumnToDisplay + 1 > width()) {
                    firstColumnToDisplay = offsetInLine + column - 6;
                } else if (pos < column && firstColumnToDisplay + 5 > pos) {
                    firstColumnToDisplay = Math.max(0, firstColumnToDisplay - width() + 5);
                }
            }
            offsetInLine = prevLineOffset(line, pos + 1).get();
            column = pos - offsetInLine;
        }

        void delete(int count) {
            while (--count >= 0 && moveRight(1) && backspace(1));
        }

        boolean backspace(int count) {
            while (count > 0) {
                String text = lines.get(line);
                int pos = offsetInLine + column;
                if (pos == 0) {
                    if (line == 0) {
                        bof();
                        return false;
                    }
                    String prev = lines.get(--line);
                    lines.set(line, prev + text);
                    offsets.set(line, computeOffsets(prev + text));
                    moveToChar(length(prev, tabs));
                    lines.remove(line + 1);
                    offsets.remove(line + 1);
                    count--;
                    dirty = true;
                } else {
                    int nb = Math.min(pos, count);
                    text = text.substring(0, pos - nb) + text.substring(pos);
                    lines.set(line, text);
                    offsets.set(line, computeOffsets(text));
                    moveToChar(offsetInLine + column - nb);
                    count -= nb;
                    dirty = true;
                }
            }
            ensureCursorVisible();
            return true;
        }

        boolean moveLeft(int chars) {
            boolean ret = true;
            while (--chars >= 0) {
                if (offsetInLine + column > 0) {
                    moveToChar(offsetInLine + column - 1);
                } else if (line > 0) {
                    line--;
                    moveToChar(length(getLine(line), tabs));
                } else {
                    bof();
                    ret = false;
                    break;
                }
            }
            wantedColumn = column;
            ensureCursorVisible();
            return ret;
        }

        boolean moveRight(int chars) {
            return moveRight(chars, false);
        }

        int width() {
            return size.getColumns() - (printLineNumbers ? 8 : 0) - (wrapping ? 0 : 1) - (firstColumnToDisplay > 0 ? 1 : 0);
        }

        boolean moveRight(int chars, boolean fromBeginning) {
            if (fromBeginning) {
                firstColumnToDisplay = 0;
                offsetInLine = 0;
                column = 0;
                chars = chars <= length(getLine(line), tabs) ? chars : length(getLine(line), tabs);
            }
            boolean ret = true;
            while (--chars >= 0) {
                int len =  length(getLine(line), tabs);
                if (offsetInLine + column + 1 <= len) {
                    moveToChar(offsetInLine + column + 1);
                } else if (getLine(line + 1) != null) {
                    line++;
                    firstColumnToDisplay = 0;
                    offsetInLine = 0;
                    column = 0;
                } else {
                    eof();
                    ret = false;
                    break;
                }
            }
            wantedColumn = column;
            ensureCursorVisible();
            return ret;
        }

        void moveDown(int lines) {
            cursorDown(lines);
            ensureCursorVisible();
        }

        void moveUp(int lines) {
            cursorUp(lines);
            ensureCursorVisible();
        }

        private Optional<Integer> prevLineOffset(int line, int offsetInLine) {
            if (line >= offsets.size()) {
                return Optional.empty();
            }
            Iterator<Integer> it = offsets.get(line).descendingIterator();
            while (it.hasNext()) {
                int off = it.next();
                if (off < offsetInLine) {
                    return Optional.of(off);
                }
            }
            return Optional.empty();
        }

        private Optional<Integer> nextLineOffset(int line, int offsetInLine) {
            if (line >= offsets.size()) {
                return Optional.empty();
            }
            return offsets.get(line).stream()
                    .filter(o -> o > offsetInLine)
                    .findFirst();
        }

        void moveDisplayDown(int lines) {
            int height = size.getRows() - computeHeader().size() - computeFooter().size();
            // Adjust cursor
            while (--lines >= 0) {
                int lastLineToDisplay = firstLineToDisplay;
                if (!wrapping) {
                    lastLineToDisplay += height - 1;
                } else {
                    int off = offsetInLineToDisplay;
                    for (int l = 0; l < height - 1; l++) {
                        Optional<Integer> next = nextLineOffset(lastLineToDisplay, off);
                        if (next.isPresent()) {
                            off = next.get();
                        } else {
                            off = 0;
                            lastLineToDisplay++;
                        }
                    }
                }
                if (getLine(lastLineToDisplay) == null) {
                    eof();
                    return;
                }
                Optional<Integer> next = nextLineOffset(firstLineToDisplay, offsetInLineToDisplay);
                if (next.isPresent()) {
                    offsetInLineToDisplay = next.get();
                } else {
                    offsetInLineToDisplay = 0;
                    firstLineToDisplay++;
                }
            }
        }

        void moveDisplayUp(int lines) {
            int width = size.getColumns() - (printLineNumbers ? 8 : 0);
            while (--lines >= 0) {
                if (offsetInLineToDisplay > 0) {
                    offsetInLineToDisplay = Math.max(0, offsetInLineToDisplay - (width - 1));
                } else if (firstLineToDisplay > 0) {
                    firstLineToDisplay--;
                    offsetInLineToDisplay = prevLineOffset(firstLineToDisplay, Integer.MAX_VALUE).get();
                } else {
                    bof();
                    return;
                }
            }
        }

        private void cursorDown(int lines) {
            // Adjust cursor
            firstColumnToDisplay = 0;
            while (--lines >= 0) {
                if (!wrapping) {
                    if (getLine(line + 1) != null) {
                        line++;
                        offsetInLine = 0;
                        column = Math.min(getLine(line).length(), wantedColumn);
                    } else {
                        bof();
                        break;
                    }
                } else {
                    String txt = getLine(line);
                    Optional<Integer> off = nextLineOffset(line, offsetInLine);
                    if (off.isPresent()) {
                        offsetInLine = off.get();
                    } else if (getLine(line + 1) == null) {
                        eof();
                        break;
                    } else {
                        line++;
                        offsetInLine = 0;
                        txt = getLine(line);
                    }
                    String curLine = txt;
                    int next = nextLineOffset(line, offsetInLine).orElseGet(curLine::length);
                    column = Math.min(wantedColumn, next - offsetInLine);
                }
            }
        }

        private void cursorUp(int lines) {
            firstColumnToDisplay = 0;
            while (--lines >= 0) {
                if (!wrapping) {
                    if (line > 0) {
                        line--;
                        column = Math.min(length(getLine(line), tabs) - offsetInLine, wantedColumn);
                    } else {
                        bof();
                        break;
                    }
                } else {
                    Optional<Integer> prev = prevLineOffset(line, offsetInLine);
                    if (prev.isPresent()) {
                        offsetInLine = prev.get();
                    } else if (line > 0) {
                        line--;
                        offsetInLine = prevLineOffset(line, Integer.MAX_VALUE).get();
                        int next = nextLineOffset(line, offsetInLine).orElse(getLine(line).length());
                        column = Math.min(wantedColumn, next - offsetInLine);
                    } else {
                        bof();
                        break;
                    }
                }
            }
        }

        void ensureCursorVisible() {
            List<AttributedString> header = computeHeader();
            int rwidth = size.getColumns();
            int height = size.getRows() - header.size() - computeFooter().size();

            while (line < firstLineToDisplay
                    || line == firstLineToDisplay && offsetInLine < offsetInLineToDisplay) {
                moveDisplayUp(smoothScrolling ? 1 : height / 2);
            }

            while (true) {
                int cursor = computeCursorPosition(header.size() * size.getColumns() + (printLineNumbers ? 8 : 0), rwidth);
                if (cursor >= (height + header.size()) * rwidth) {
                    moveDisplayDown(smoothScrolling ? 1 : height / 2);
                } else {
                    break;
                }
            }
        }

        void eof() {
        }

        void bof() {
        }

        void resetDisplay() {
// mrn 29/7/2019 same calculation as in endOfLine() & nextSearch() methods that were failing
//               change also here although haven't seen an error
//            int width = size.getColumns() - (printLineNumbers ? 8 : 0);
            column = offsetInLine + column;
//            offsetInLine = (column / width) * (width - 1);
//            column = column - offsetInLine;
            moveRight(column, true);
        }

        String getLine(int line) {
            return line < lines.size() ? lines.get(line) : null;
        }

        String getTitle() {
            return file != null ? "File: " + file : "New Buffer";
        }

        List<AttributedString> computeHeader() {
            String left = Nano.this.getTitle();
            String middle = null;
            String right = dirty ? "Modified" : "        ";

            int width = size.getColumns();
            int mstart = 2 + left.length() + 1;
            int mend = width - 2 - 8;

            if (file == null) {
                middle = "New Buffer";
            } else {
                int max = mend - mstart;
                String src = file;
                if ("File: ".length() + src.length() > max) {
                    int lastSep = src.lastIndexOf('/');
                    if (lastSep > 0) {
                        String p1 = src.substring(lastSep);
                        String p0 = src.substring(0, lastSep);
                        while (p0.startsWith(".")) {
                            p0 = p0.substring(1);
                        }
                        int nb = max - p1.length() - "File: ...".length();
                        int cut;
                        cut = Math.max(0, Math.min(p0.length(), p0.length() - nb));
                        middle = "File: ..." + p0.substring(cut, p0.length()) + p1;
                    }
                    if (middle == null || middle.length() > max) {
                        left = null;
                        max = mend - 2;
                        int nb = max - "File: ...".length();
                        int cut = Math.max(0, Math.min(src.length(), src.length() - nb));
                        middle = "File: ..." + src.substring(cut, src.length());
                        if (middle.length() > max) {
                            middle = middle.substring(0, max);
                        }
                    }
                } else {
                    middle = "File: " + src;
                }
            }

            int pos = 0;
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(AttributedStyle.INVERSE);
            sb.append("  ");
            pos += 2;

            if (left != null) {
                sb.append(left);
                pos += left.length();
                sb.append(" ");
                pos += 1;
                for (int i = 1; i < (size.getColumns() - middle.length()) / 2 - left.length() - 1 - 2; i++) {
                    sb.append(" ");
                    pos++;
                }
            }
            sb.append(middle);
            pos += middle.length();
            while (pos < width - 8 - 2) {
                sb.append(" ");
                pos++;
            }
            sb.append(right);
            sb.append("  \n");
            if (oneMoreLine) {
                return Collections.singletonList(sb.toAttributedString());
            } else {
                return Arrays.asList(sb.toAttributedString(), new AttributedString("\n"));
            }
        }

        void highlightDisplayedLine(int curLine, int curOffset, int nextOffset, AttributedStringBuilder line){
            AttributedString disp = syntaxHighlighter.highlightNextLine(new AttributedString(getLine(curLine)));
            if (!mark) {
                line.append(disp.columnSubSequence(curOffset, nextOffset));
            } else if (getMarkStart()[0] == getMarkEnd()[0]) {
                if (curLine == getMarkStart()[0]) {
                    if (getMarkStart()[1] > nextOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset));
                    } else if (getMarkStart()[1] <  curOffset) {
                        if (getMarkEnd()[1] > nextOffset) {
                            line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                        } else if (getMarkEnd()[1] > curOffset) {
                            line.append(disp.columnSubSequence(curOffset, getMarkEnd()[1]), AttributedStyle.INVERSE);
                            line.append(disp.columnSubSequence(getMarkEnd()[1], nextOffset));
                        } else {
                            line.append(disp.columnSubSequence(curOffset, nextOffset));
                        }
                    } else {
                        line.append(disp.columnSubSequence(curOffset, getMarkStart()[1]));
                        if (getMarkEnd()[1] > nextOffset) {
                            line.append(disp.columnSubSequence(getMarkStart()[1], nextOffset), AttributedStyle.INVERSE);
                        } else {
                            line.append(disp.columnSubSequence(getMarkStart()[1], getMarkEnd()[1]), AttributedStyle.INVERSE);
                            line.append(disp.columnSubSequence(getMarkEnd()[1], nextOffset));
                        }
                    }
                } else {
                    line.append(disp.columnSubSequence(curOffset, nextOffset));
                }
            } else {
                if (curLine > getMarkStart()[0] && curLine < getMarkEnd()[0]) {
                    line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                } else if (curLine == getMarkStart()[0]) {
                    if (getMarkStart()[1] > nextOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset));
                    } else if (getMarkStart()[1] < curOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                    } else {
                        line.append(disp.columnSubSequence(curOffset, getMarkStart()[1]));
                        line.append(disp.columnSubSequence(getMarkStart()[1], nextOffset), AttributedStyle.INVERSE);
                    }
                } else if (curLine == getMarkEnd()[0]) {
                    if (getMarkEnd()[1] < curOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset));
                    } else if (getMarkEnd()[1] > nextOffset) {
                        line.append(disp.columnSubSequence(curOffset, nextOffset), AttributedStyle.INVERSE);
                    } else {
                        line.append(disp.columnSubSequence(curOffset, getMarkEnd()[1]), AttributedStyle.INVERSE);
                        line.append(disp.columnSubSequence(getMarkEnd()[1], nextOffset));
                    }
                } else {
                    line.append(disp.columnSubSequence(curOffset, nextOffset));
                }
            }
        }

        /*
         * Hardcoded syntax patterns... these should be read from config file
         * Patterns could be get from
         * https://github.com/scopatz/nanorc
         * and converted prefibilmente automatically to java format when reading
         * config
         */
        private SyntaxHighlighter doSyntaxHighlighter() {
            AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT);
            SyntaxHighlighter out = new SyntaxHighlighter();
            if (file == null) {
                // do nothing
            } else if (file.endsWith(".java")) {
                out.addRule(s.foreground(AttributedStyle.GREEN)
                        , Pattern.compile("\\b(boolean|byte|char|double|float|int|long|new|short|this"
                                        + "|transient|void)\\b"));
                out.addRule(s.foreground(AttributedStyle.RED)
                        , Pattern.compile("\\b(break|case|catch|continue|default|do|else|finally|for" 
                                        + "|if|return|switch|throw|try|while)\\b"));
                out.addRule(s.foreground(AttributedStyle.CYAN)
                        , Pattern.compile("\\b(abstract|class|extends|final|implements|import|instanceof"
                                        + "|interface|native|package|private|protected|public|static|strictfp"
                                        + "|super|synchronized|throws|volatile)\\b"));
                out.addRule(s.foreground(AttributedStyle.RED), Pattern.compile("\"[^\"]*\""));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("\\b(true|false|null)\\b"));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("\\b(([1-9][0-9]+)|0+)\\.[0-9]+\\b"));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("\\b[1-9][0-9]*\\b"));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("\\b0[0-7]*\\b"));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("\\b0x[1-9a-f][0-9a-f]*\\b"));
                out.addRule(s.foreground(AttributedStyle.BLUE), Pattern.compile("//.*"));
                out.addRule(s.foreground(AttributedStyle.BLUE), Pattern.compile("/\\*"), Pattern.compile("\\*/"));
                out.addRule(s.foreground(AttributedStyle.BLUE + AttributedStyle.BRIGHT), Pattern.compile("/\\*\\*"), Pattern.compile("\\*/"));
                out.addRule(s.background(AttributedStyle.GREEN), Pattern.compile("\\s+$"));
            } else if (file.endsWith(".xml")) {
                out.addRule(s.foreground(AttributedStyle.WHITE), Pattern.compile("^.+$"));
                out.addRule(s.foreground(AttributedStyle.GREEN), Pattern.compile("<"), Pattern.compile(">"));
                out.addRule(s.foreground(AttributedStyle.CYAN), Pattern.compile("<[^> ]+"));
                out.addRule(s.foreground(AttributedStyle.MAGENTA), Pattern.compile("\"[^\"]*\""));
                out.addRule(s.foreground(AttributedStyle.CYAN), Pattern.compile(">"));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("<!DOCTYPE"), Pattern.compile("[/]?>"));
                out.addRule(s.foreground(AttributedStyle.YELLOW), Pattern.compile("<!--"), Pattern.compile("-->"));
                out.addRule(s.foreground(AttributedStyle.RED), Pattern.compile("&[^;]*;"));
                out.addRule(s.background(AttributedStyle.GREEN), Pattern.compile("\\s+$"));
            }
            return out;
        }

        List<AttributedString> getDisplayedLines(int nbLines) {
            AttributedStyle s = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT);
            AttributedString cut = new AttributedString("…", s);
            AttributedString ret = new AttributedString("↩", s);

            List<AttributedString> newLines = new ArrayList<>();
            int rwidth = size.getColumns();
            int width = rwidth - (printLineNumbers ? 8 : 0);
            int curLine = firstLineToDisplay;
            int curOffset = offsetInLineToDisplay;
            int prevLine = -1;
            syntaxHighlighter.reset();
            for (int terminalLine = 0; terminalLine < nbLines; terminalLine++) {
                AttributedStringBuilder line = new AttributedStringBuilder().tabs(tabs);
                if (printLineNumbers && curLine < lines.size()) {
                    line.style(s);
                    if (curLine != prevLine) {
                        line.append(String.format("%7d ", curLine + 1));
                    } else {
                        line.append("      ‧ ");
                    }
                    line.style(AttributedStyle.DEFAULT);
                    prevLine = curLine;
                }
                if (curLine >= lines.size()) {
                    // Nothing to do
                } else if (!wrapping) {
                    AttributedString disp = new AttributedString(getLine(curLine));
                    if (this.line == curLine) {
                        int cutCount = 1;
                        if (firstColumnToDisplay > 0) {
                            line.append(cut);
                            cutCount = 2;
                        }
                        if (disp.columnLength() - firstColumnToDisplay >= width - (cutCount - 1)*cut.columnLength()) {
                            highlightDisplayedLine(curLine, firstColumnToDisplay
                                , firstColumnToDisplay + width - cutCount*cut.columnLength(), line);
                            line.append(cut);
                        } else {
                            highlightDisplayedLine(curLine, firstColumnToDisplay, disp.columnLength(), line);
                        }
                    } else {
                        if (disp.columnLength() >= width) {
                            highlightDisplayedLine(curLine, 0, width - cut.columnLength(), line);
                            line.append(cut);
                        } else {
                            highlightDisplayedLine(curLine, 0, disp.columnLength(), line);
                        }
                    }
                    curLine++;
                } else {
                    Optional<Integer> nextOffset = nextLineOffset(curLine, curOffset);
                    if (nextOffset.isPresent()) {
                        highlightDisplayedLine(curLine, curOffset, nextOffset.get(), line);
                        line.append(ret);
                        curOffset = nextOffset.get();
                    } else {
                        highlightDisplayedLine(curLine, curOffset, Integer.MAX_VALUE, line);
                        curLine++;
                        curOffset = 0;
                    }
                }
                line.append('\n');
                newLines.add(line.toAttributedString());
            }
            return newLines;
        }

        public void moveTo(int x, int y) {
            if (printLineNumbers) {
                x = Math.max(x - 8, 0);
            }
            line = firstLineToDisplay;
            offsetInLine = offsetInLineToDisplay;
            wantedColumn = x;
            cursorDown(y);
        }

        public void gotoLine(int x, int y) {
            line = y < lines.size() ? y : lines.size() - 1;
            x = x <= length(lines.get(line), tabs) ? x : length(lines.get(line), tabs);
            firstLineToDisplay = line > 0 ? line - 1 : line;
            offsetInLine = 0;
            offsetInLineToDisplay = 0;
            column = 0;
            moveRight(x);
        }

        public int getDisplayedCursor() {
            return computeCursorPosition(printLineNumbers ? 8 : 0, size.getColumns() + 1);
        }

        private int computeCursorPosition(int cursor, int rwidth) {
            int cur = firstLineToDisplay;
            int off = offsetInLineToDisplay;
            while (true) {
                if (cur < line || off < offsetInLine) {
                    if (!wrapping) {
                        cursor += rwidth;
                        cur++;
                    } else {
                        cursor += rwidth;
                        Optional<Integer> next = nextLineOffset(cur, off);
                        if (next.isPresent()) {
                            off = next.get();
                        } else {
                            cur++;
                            off = 0;
                        }
                    }
                } else if (cur == line) {
                    if (!wrapping && column > firstColumnToDisplay + width()) {
                        while (column > firstColumnToDisplay + width()) {
                            firstColumnToDisplay += width();
                        }
                    }
                    cursor += column - firstColumnToDisplay + (firstColumnToDisplay > 0 ? 1 : 0);
                    break;
                } else {
                    throw new IllegalStateException();
                }
            }
            return cursor;
        }

        char getCurrentChar() {
            String str = lines.get(line);
            if (column + offsetInLine < str.length()) {
                return str.charAt(column + offsetInLine);
            } else if (line < lines.size() - 1) {
                return '\n';
            } else {
                return 0;
            }
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public void prevWord() {
            while (Character.isAlphabetic(getCurrentChar())
                    && moveLeft(1));
            while (!Character.isAlphabetic(getCurrentChar())
                    && moveLeft(1));
            while (Character.isAlphabetic(getCurrentChar())
                    && moveLeft(1));
            moveRight(1);
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public void nextWord() {
            while (Character.isAlphabetic(getCurrentChar())
                    && moveRight(1));
            while (!Character.isAlphabetic(getCurrentChar())
                    && moveRight(1));
        }

        public void beginningOfLine() {
            column = offsetInLine = 0;
            wantedColumn = 0;
            ensureCursorVisible();
        }

        public void endOfLine() {
            int x = length(lines.get(line), tabs);
            moveRight(x, true);
        }

        public void prevPage() {
            int height = size.getRows() - computeHeader().size() - computeFooter().size();
            scrollUp(height - 2);
            column = 0;
            firstLineToDisplay = line;
            offsetInLineToDisplay = offsetInLine;
        }

        public void nextPage() {
            int height = size.getRows() - computeHeader().size() - computeFooter().size();
            scrollDown(height - 2);
            column = 0;
            firstLineToDisplay = line;
            offsetInLineToDisplay = offsetInLine;
        }

        public void scrollUp(int lines) {
            cursorUp(lines);
            moveDisplayUp(lines);
        }

        public void scrollDown(int lines) {
            cursorDown(lines);
            moveDisplayDown(lines);
        }

        public void firstLine() {
            line = 0;
            offsetInLine = column = 0;
            ensureCursorVisible();
        }

        public void lastLine() {
            line = lines.size() - 1;
            offsetInLine = column = 0;
            ensureCursorVisible();
        }

        void nextSearch() {
            if (searchTerm == null) {
                setMessage("No current search pattern");
                return;
            }
            setMessage(null);
            int cur = line;
            int dir = searchBackwards ? -1 : +1;
            int newPos = -1;
            int newLine = -1;
            // Search on current line
            List<Integer> curRes = doSearch(lines.get(line));
            if (searchBackwards) {
                Collections.reverse(curRes);
            }
            for (int r : curRes) {
                if (searchBackwards ? r < offsetInLine + column : r > offsetInLine + column) {
                    newPos = r;
                    newLine = line;
                    break;
                }
            }
            // Check other lines
            if (newPos < 0) {
                while (true) {
                    cur = (cur + dir + lines.size()) % lines.size();
                    if (cur == line) {
                        break;
                    }
                    List<Integer> res = doSearch(lines.get(cur));
                    if (!res.isEmpty()) {
                        newPos = searchBackwards ? res.get(res.size() - 1) : res.get(0);
                        newLine = cur;
                        break;
                    }
                }
            }
            if (newPos < 0) {
                if (!curRes.isEmpty()) {
                    newPos = curRes.get(0);
                    newLine = line;
                }
            }
            if (newPos >= 0) {
                if (newLine == line && newPos == offsetInLine + column) {
                    setMessage("This is the only occurence");
                    return;
                }
                if ((searchBackwards && (newLine > line || (newLine == line && newPos > offsetInLine + column)))
                    || (!searchBackwards && (newLine < line || (newLine == line && newPos < offsetInLine + column)))) {
                    setMessage("Search Wrapped");
                }
                line = newLine;
                moveRight(newPos, true);
            } else {
                setMessage("\"" + searchTerm + "\" not found");
            }
        }

        private List<Integer> doSearch(String text) {
            Pattern pat = Pattern.compile(searchTerm,
                    (searchCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            | (searchRegexp ? 0 : Pattern.LITERAL));
            Matcher m = pat.matcher(text);
            List<Integer> res = new ArrayList<>();
            while (m.find()) {
                res.add(m.start());
            }
            return res;
        }

        public void matching() {
            int opening = getCurrentChar();
            int idx = matchBrackets.indexOf(opening);
            if (idx >= 0) {
                int dir = (idx >= matchBrackets.length() / 2) ? -1 : +1;
                int closing = matchBrackets.charAt((idx + matchBrackets.length() / 2) % matchBrackets.length());

                int lvl = 1;
                int cur = line;
                int pos = offsetInLine + column;
                while (true) {
                    if ((pos + dir >= 0) && (pos + dir < getLine(cur).length())) {
                        pos += dir;
                    } else if ((cur + dir >= 0) && (cur + dir < lines.size())) {
                        cur += dir;
                        pos = dir > 0 ? 0 : lines.get(cur).length() - 1;
                        // Skip empty lines
                        if (pos < 0 || pos >= lines.get(cur).length()) {
                            continue;
                        }
                    } else {
                        setMessage("No matching bracket");
                        return;
                    }
                    int c = lines.get(cur).charAt(pos);
                    if (c == opening) {
                        lvl++;
                    } else if (c == closing) {
                        if (--lvl == 0) {
                            line = cur;
                            moveToChar(pos);
                            ensureCursorVisible();
                            return;
                        }
                    }
                }
            } else {
                setMessage("Not a bracket");
            }
        }

        private int length(String line, int tabs) {
            return new AttributedStringBuilder().tabs(tabs).append(line).columnLength();
        }

        void copy() {
            if (uncut || cut2end || mark) {
                cutbuffer = new ArrayList<>();
            }
            if (mark) {
                int[] s = getMarkStart();
                int[] e = getMarkEnd();
                if (s[0] == e[0]) {
                    cutbuffer.add(lines.get(s[0]).substring(s[1], e[1]));
                } else {
                    if (s[1] != 0) {
                        cutbuffer.add(lines.get(s[0]).substring(s[1]));
                        s[0] = s[0] + 1;
                    }
                    for (int i = s[0]; i < e[0]; i++) {
                        cutbuffer.add(lines.get(i));
                    }
                    if (e[1] != 0) {
                        cutbuffer.add(lines.get(e[0]).substring(0, e[1]));
                    }
                }
                mark = false;
                mark();
            } else if (cut2end) {
                String l = lines.get(line);
                int col = offsetInLine + column;
                cutbuffer.add(l.substring(col));
                moveRight(l.substring(col).length());
            } else {
                cutbuffer.add(lines.get(line));
                cursorDown(1);
            }
            uncut = false;
        }

        void cut() {
            cut(false);
        }

        void cut(boolean toEnd) {
            if (lines.size() > 1) {
                if (uncut || cut2end || toEnd || mark) {
                    cutbuffer = new ArrayList<>();
                }
                if (mark) {
                    int[] s = getMarkStart();
                    int[] e = getMarkEnd();
                    if (s[0] == e[0]) {
                        String l = lines.get(s[0]);
                        int cols = s[1];
                        int cole = e[1];
                        cutbuffer.add(l.substring(cols, cole));
                        lines.set(s[0], l.substring(0, cols) + l.substring(cole));
                        computeAllOffsets();
                        moveRight(cols, true);
                    } else {
                        int ls = s[0];
                        int cs = s[1];
                        if (s[1] != 0) {
                            String l = lines.get(s[0]);
                            int col = s[1];
                            cutbuffer.add(l.substring(col));
                            lines.set(s[0], l.substring(0, col));
                            s[0] = s[0] + 1;
                        }
                        for (int i = s[0]; i < e[0]; i++) {
                            cutbuffer.add(lines.get(s[0]));
                            lines.remove(s[0]);
                        }
                        if (e[1] != 0) {
                            String l = lines.get(s[0]);
                            int col = e[1];
                            cutbuffer.add(l.substring(0, col));
                            lines.set(s[0], l.substring(col));
                        }
                        computeAllOffsets();
                        gotoLine(cs, ls);
                    }
                    mark = false;
                    mark();
                } else if (cut2end || toEnd) {
                    String l = lines.get(line);
                    int col = offsetInLine + column;
                    cutbuffer.add(l.substring(col));
                    lines.set(line, l.substring(0, col));
                    if (toEnd) {
                        line++;
                        while (true) {
                            cutbuffer.add(lines.get(line));
                            lines.remove(line);
                            if (line > lines.size() - 1) {
                                line--;
                                break;
                            }
                        }
                    }
                } else {
                    cutbuffer.add(lines.get(line));
                    lines.remove(line);
                    offsetInLine = 0;
                    if (line > lines.size() - 1) {
                        line--;
                    }
                }
                display.clear();
                computeAllOffsets();
                dirty = true;
                uncut = false;
            }
        }

        void uncut() {
            if (cutbuffer.isEmpty()) {
                return;
            }
            String l = lines.get(line);
            int col = offsetInLine + column;
            if (cut2end) {
                lines.set(line, l.substring(0, col) + cutbuffer.get(0) + l.substring(col));
                computeAllOffsets();
                moveRight(col + cutbuffer.get(0).length(), true);
            } else if (col == 0) {
                lines.addAll(line, cutbuffer);
                computeAllOffsets();
                if (cutbuffer.size() > 1) {
                    gotoLine(cutbuffer.get(cutbuffer.size() - 1).length(), line + cutbuffer.size());
                } else {
                    moveRight(cutbuffer.get(0).length(), true);
                }
            } else {
                int gotol = line;
                if (cutbuffer.size() == 1) {
                    lines.set(line, l.substring(0, col) + cutbuffer.get(0) + l.substring(col));
                } else {
                    lines.set(line++, l.substring(0, col) + cutbuffer.get(0));
                    gotol = line;
                    lines.add(line, cutbuffer.get(cutbuffer.size() - 1) + l.substring(col));
                    for (int i = cutbuffer.size() - 2; i > 0 ; i--) {
                        gotol++;
                        lines.add(line, cutbuffer.get(i));
                    }
                }
                computeAllOffsets();
                if (cutbuffer.size() > 1) {
                    gotoLine(cutbuffer.get(cutbuffer.size() - 1).length(), gotol);
                } else {
                    moveRight(col + cutbuffer.get(0).length(), true);
                }
            }
            display.clear();
            dirty = true;
            uncut = true;
        }

        void mark() {
            if (mark) {
                markPos[0] = line;
                markPos[1] = offsetInLine + column;
            } else {
                markPos[0] = -1;
                markPos[1] = -1;
            }
        }

        int[] getMarkStart() {
            int[] out = {-1, -1};
            if (!mark) {
                return out;
            }
            if (markPos[0] > line || (markPos[0] == line && markPos[1] > offsetInLine + column) ) {
                out[0] = line;
                out[1] = offsetInLine + column;
            } else {
                out = markPos;
            }
            return out;
        }

        int[] getMarkEnd() {
            int[] out = {-1, -1};
            if (!mark) {
                return out;
            }
            if (markPos[0] > line || (markPos[0] == line && markPos[1] > offsetInLine + column) ) {
                out = markPos;
            } else {
                out[0] = line;
                out[1] = offsetInLine + column;
            }
            return out;
        }
    }

    private static class SyntaxHighlighter {
        private List<HighlightRule> rules = new ArrayList<>();
        private int ruleStartId = 0;

        public SyntaxHighlighter() {}

        public void addRule(AttributedStyle style, Pattern pattern) {
            rules.add(new HighlightRule(style, pattern));
        }

        public void addRule(AttributedStyle style, Pattern start, Pattern end) {
            rules.add(new HighlightRule(style, start, end));
        }

        public void reset() {
            ruleStartId = 0;
        }

        public AttributedString highlightNextLine(String line) {
            return highlightNextLine(new AttributedString(line));
        }

        public AttributedString highlightNextLine(AttributedString line) {
            if (rules.isEmpty()) {
                return line;
            }
            AttributedStringBuilder asb = new AttributedStringBuilder();
            AttributedStyle as = new AttributedStyle(AttributedStyle.DEFAULT);
            asb.append(line);
            for (int i = ruleStartId; i < rules.size(); i++) {
                HighlightRule rule = rules.get(i);
                switch (rule.getType()) {
                case PATTERN:
                    asb.styleMatches(rule.getPattern(), rule.getStyle());
                    break;
                case START_END:
                    boolean done = false;
                    Matcher start = rule.getStart().matcher(asb.toAttributedString());
                    Matcher end = rule.getEnd().matcher(asb.toAttributedString());
                    while (!done) {
                        AttributedStringBuilder a = new AttributedStringBuilder();
                        if (ruleStartId == i) { // first rule should never be type
                                                // START_END or we will fail here!
                            if (end.find()) {
                                a.append(asb.columnSubSequence(0, end.end()),rule.getStyle());
                                a.append(asb.columnSubSequence(end.end(), asb.length()));
                                ruleStartId = 0;
                            } else {
                                a.append(asb, rule.getStyle());
                                done = true;
                            }
                            asb = a;
                        } else {
                            if (start.find()) {
                                a.append(asb.columnSubSequence(0, start.start()));
                                if (end.find()) {
                                    a.append(asb.columnSubSequence(start.start(), end.end()), rule.getStyle());
                                    a.append(asb.columnSubSequence(end.end(), asb.length()));
                                } else {
                                    ruleStartId = i;
                                    a.append(asb.columnSubSequence(start.start(),asb.length()), rule.getStyle());
                                    done = true;
                                }
                                asb = a;
                            } else {
                                done = true;
                            }
                        }
                    }
                    break;
                }
            }
            return asb.toAttributedString();
        }

        private static class HighlightRule {
            public enum RuleType {PATTERN, START_END};
            private RuleType type;
            private Pattern pattern;
            private AttributedStyle style;
            private Pattern start;
            private Pattern end;

            public HighlightRule(AttributedStyle style, Pattern pattern) {
                 this.type = RuleType.PATTERN;
                 this.pattern = pattern;
                 this.style = style;
            }

            public HighlightRule(AttributedStyle style, Pattern start, Pattern end) {
                 this.type = RuleType.START_END;
                 this.style = style;
                 this.start = start;
                 this.end = end;
            }

            public RuleType getType() {
                return type;
            }

            public AttributedStyle getStyle() {
                return style;
            }

            public Pattern getPattern() {
                if (type == RuleType.START_END) {
                    throw new IllegalAccessError();
                }
                return pattern;
            }

            public Pattern getStart() {
                if (type == RuleType.PATTERN) {
                    throw new IllegalAccessError();
                }
                return start;
            }

            public Pattern getEnd() {
                if (type == RuleType.PATTERN) {
                    throw new IllegalAccessError();
                }
                return end;
            }

        }
    }

    public Nano(Terminal terminal, File root) {
        this(terminal, root.toPath());
    }

    public Nano(Terminal terminal, Path root) {
        this(terminal, root, null);
    }

    public Nano(Terminal terminal, Path root, Options opts) {
        this.terminal = terminal;
        this.root = root;
        this.display = new Display(terminal, true);
        this.bindingReader = new BindingReader(terminal.reader());
        this.size = new Size();
        this.restricted = opts != null && opts.isSet("restricted");
        this.vsusp = terminal.getAttributes().getControlChar(ControlChar.VSUSP);
        bindKeys();
    }

    public void open(String... files) throws IOException {
        open(Arrays.asList(files));
    }

    public void open(List<String> files) throws IOException {
        for (String file : files) {
            if (file.contains("*") || file.contains("?")) {
                PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:"+file);
                Files.find(root, Integer.MAX_VALUE, (path, f) -> pathMatcher.matches(path))
                     .forEach(p -> buffers.add(new Buffer(p.toString())));
            } else {
                buffers.add(new Buffer(file));
            }
        }
    }

    public void run() throws IOException {
        if (buffers.isEmpty()) {
            buffers.add(new Buffer(null));
        }
        buffer = buffers.get(bufferIndex);

        Attributes attributes = terminal.getAttributes();
        Attributes newAttr = new Attributes(attributes);
        newAttr.setLocalFlags(EnumSet.of(LocalFlag.ICANON, LocalFlag.ECHO, LocalFlag.IEXTEN), false);
        newAttr.setInputFlags(EnumSet.of(InputFlag.IXON, InputFlag.ICRNL, InputFlag.INLCR), false);
        newAttr.setControlChar(ControlChar.VMIN, 1);
        newAttr.setControlChar(ControlChar.VTIME, 0);
        newAttr.setControlChar(ControlChar.VINTR, 0);
        if (restricted) {
            newAttr.setControlChar(ControlChar.VSUSP, 0);
        }
        terminal.setAttributes(newAttr);
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.keypad_xmit);
        if (mouseSupport) {
            terminal.trackMouse(Terminal.MouseTracking.Normal);
        }

        this.shortcuts = standardShortcuts();

        SignalHandler prevHandler = null;
        Status status = Status.getStatus(terminal, false);
        try {
            size.copy(terminal.getSize());
            if (status != null) {
                status.suspend();
            }
            buffer.open();
            if (buffer.file != null) {
                setMessage("Read " + buffer.lines.size() + " lines");
            }

            display.clear();
            display.reset();
            display.resize(size.getRows(), size.getColumns());
            prevHandler = terminal.handle(Signal.WINCH, this::handle);

            display();

            while (true) {
                Operation op;
                switch (op = readOperation(keys)) {
                    case QUIT:
                        if (quit()) {
                            return;
                        }
                        break;
                    case WRITE:
                        write();
                        break;
                    case READ:
                        read();
                        break;
                    case UP:
                        buffer.moveUp(1);
                        break;
                    case DOWN:
                        buffer.moveDown(1);
                        break;
                    case LEFT:
                        buffer.moveLeft(1);
                        break;
                    case RIGHT:
                        buffer.moveRight(1);
                        break;
                    case INSERT:
                        buffer.insert(bindingReader.getLastBinding());
                        break;
                    case BACKSPACE:
                        buffer.backspace(1);
                        break;
                    case DELETE:
                        buffer.delete(1);
                        break;
                    case WRAP:
                        wrap();
                        break;
                    case NUMBERS:
                        numbers();
                        break;
                    case SMOOTH_SCROLLING:
                        smoothScrolling();
                        break;
                    case MOUSE_SUPPORT:
                        mouseSupport();
                        break;
                    case ONE_MORE_LINE:
                        oneMoreLine();
                        break;
                    case CLEAR_SCREEN:
                        clearScreen();
                        break;
                    case PREV_BUFFER:
                        prevBuffer();
                        break;
                    case NEXT_BUFFER:
                        nextBuffer();
                        break;
                    case CUR_POS:
                        curPos();
                        break;
                    case PREV_WORD:
                        buffer.prevWord();
                        break;
                    case NEXT_WORD:
                        buffer.nextWord();
                        break;
                    case BEGINNING_OF_LINE:
                        buffer.beginningOfLine();
                        break;
                    case END_OF_LINE:
                        buffer.endOfLine();
                        break;
                    case FIRST_LINE:
                        buffer.firstLine();
                        break;
                    case LAST_LINE:
                        buffer.lastLine();
                        break;
                    case PREV_PAGE:
                        buffer.prevPage();
                        break;
                    case NEXT_PAGE:
                        buffer.nextPage();
                        break;
                    case SCROLL_UP:
                        buffer.scrollUp(1);
                        break;
                    case SCROLL_DOWN:
                        buffer.scrollDown(1);
                        break;
                    case SEARCH:
                        search();
                        break;
                    case NEXT_SEARCH:
                        buffer.nextSearch();
                        break;
                    case HELP:
                        help("nano-main-help.txt");
                        break;
                    case CONSTANT_CURSOR:
                        constantCursor();
                        break;
                    case VERBATIM:
                        buffer.insert(new String(Character.toChars(bindingReader.readCharacter())));
                        break;
                    case MATCHING:
                        buffer.matching();
                        break;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    case TOGGLE_SUSPENSION:
                        toggleSuspension();
                        break;
                    case COPY:
                        buffer.copy();
                        break;
                    case CUT:
                        buffer.cut();
                        break;
                    case UNCUT:
                        buffer.uncut();
                        break;
                    case GOTO:
                        gotoLine();
                        curPos();
                        break;
                    case CUT_TO_END_TOGGLE:
                        cut2end = !cut2end;
                        setMessage("Cut to end " + (cut2end ? "enabled" : "disabled"));
                        break;
                    case CUT_TO_END:
                        buffer.cut(true);
                        break;
                    case MARK:
                        mark = !mark;
                        setMessage("Mark " + (mark ? "Set" : "Unset"));
                        buffer.mark();
                        break;
                    default:
                        setMessage("Unsupported " + op.name().toLowerCase().replace('_', '-'));
                        break;
                }
                display();
            }
        } finally {
            if (mouseSupport) {
                terminal.trackMouse(Terminal.MouseTracking.Off);
            }
            terminal.puts(Capability.exit_ca_mode);
            terminal.puts(Capability.keypad_local);
            terminal.flush();
            terminal.setAttributes(attributes);
            terminal.handle(Signal.WINCH, prevHandler);
            if (status != null) {
                status.restore();
            }
       }
    }

    private int editInputBuffer(Operation operation, int curPos) {
        switch (operation) {
        case INSERT:
            editBuffer.insert(curPos++, bindingReader.getLastBinding());
            break;
        case BACKSPACE:
            if (curPos > 0) {
                editBuffer.deleteCharAt(--curPos);
            }
            break;
        case LEFT:
            if (curPos > 0) {
                curPos--;
            }
            break;
        case RIGHT:
            if (curPos < editBuffer.length()) {
                curPos++;
            }
            break;
        }
        return curPos;
    }

    boolean write() throws IOException {
        KeyMap<Operation> writeKeyMap = new KeyMap<>();
        if (!restricted) {
            writeKeyMap.setUnicode(Operation.INSERT);
            for (char i = 32; i < 256; i++) {
                writeKeyMap.bind(Operation.INSERT, Character.toString(i));
            }
            for (char i = 'A'; i <= 'Z'; i++) {
                writeKeyMap.bind(Operation.DO_LOWER_CASE, alt(i));
            }
            writeKeyMap.bind(Operation.BACKSPACE, del());
            writeKeyMap.bind(Operation.APPEND_MODE, alt('a'));
            writeKeyMap.bind(Operation.PREPEND_MODE, alt('p'));
            writeKeyMap.bind(Operation.BACKUP, alt('b'));
            writeKeyMap.bind(Operation.TO_FILES, ctrl('T'));
        }
        writeKeyMap.bind(Operation.MAC_FORMAT, alt('m'));
        writeKeyMap.bind(Operation.DOS_FORMAT, alt('d'));
        writeKeyMap.bind(Operation.ACCEPT, "\r");
        writeKeyMap.bind(Operation.CANCEL, ctrl('C'));
        writeKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        writeKeyMap.bind(Operation.MOUSE_EVENT, key(terminal, Capability.key_mouse));
        writeKeyMap.bind(Operation.TOGGLE_SUSPENSION, alt('z'));
        writeKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        writeKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));

        editMessage = getWriteMessage();
        editBuffer.setLength(0);
        editBuffer.append(buffer.file == null ? "" : buffer.file);
        int curPos = editBuffer.length();
        this.shortcuts = writeShortcuts();
        display(curPos);
        while (true) {
            Operation op = readOperation(writeKeyMap);
            switch (op) {
                case CANCEL:
                    editMessage = null;
                    this.shortcuts = standardShortcuts();
                    return false;
                case ACCEPT:
                    editMessage = null;
                    if (save(editBuffer.toString())) {
                        this.shortcuts = standardShortcuts();
                        return true;
                    }
                    return false;
                case HELP:
                    help("nano-write-help.txt");
                    break;
                case MAC_FORMAT:
                    buffer.format = (buffer.format == WriteFormat.MAC) ? WriteFormat.UNIX : WriteFormat.MAC;
                    break;
                case DOS_FORMAT:
                    buffer.format = (buffer.format == WriteFormat.DOS) ? WriteFormat.UNIX : WriteFormat.DOS;
                    break;
                case APPEND_MODE:
                    writeMode = (writeMode == WriteMode.APPEND) ? WriteMode.WRITE : WriteMode.APPEND;
                    break;
                case PREPEND_MODE:
                    writeMode = (writeMode == WriteMode.PREPEND) ? WriteMode.WRITE : WriteMode.PREPEND;
                    break;
                case BACKUP:
                    writeBackup = !writeBackup;
                    break;
                case MOUSE_EVENT:
                    mouseEvent();
                    break;
                case TOGGLE_SUSPENSION:
                    toggleSuspension();
                    break;
                default:
                    curPos = editInputBuffer(op, curPos);
                    break;
            }
            editMessage = getWriteMessage();
            display(curPos);
        }
    }

    private Operation readOperation(KeyMap<Operation> keymap) {
        while (true) {
            Operation op = bindingReader.readBinding(keymap);
            if (op == Operation.DO_LOWER_CASE) {
                bindingReader.runMacro(bindingReader.getLastBinding().toLowerCase());
            } else {
                return op;
            }
        }
    }

    private boolean save(String name) throws IOException {
        Path orgPath = buffer.file != null ? root.resolve(buffer.file) : null;
        Path newPath = root.resolve(name);
        boolean isSame = orgPath != null && Files.exists(orgPath) && Files.exists(newPath) && Files.isSameFile(orgPath, newPath);
        if (!isSame && Files.exists(Paths.get(name)) && writeMode == WriteMode.WRITE) {
            Operation op = getYNC("File exists, OVERWRITE ? ");
            if (op != Operation.YES) {
                return false;
            }
        } else if (!Files.exists(newPath)) {
            newPath.toFile().createNewFile();
        }
        Path t = Files.createTempFile("jline-", ".temp");
        try (OutputStream os = Files.newOutputStream(t, StandardOpenOption.WRITE,
                                                        StandardOpenOption.TRUNCATE_EXISTING,
                                                        StandardOpenOption.CREATE)) {
            if (writeMode == WriteMode.APPEND) {
                if (Files.isReadable(newPath)) {
                    Files.copy(newPath, os);
                }
            }
            Writer w = new OutputStreamWriter(os, buffer.charset);
            for (int i = 0; i < buffer.lines.size(); i++) {
                w.write(buffer.lines.get(i));
                switch (buffer.format) {
                    case UNIX:
                        w.write("\n");
                        break;
                    case DOS:
                        w.write("\r\n");
                        break;
                    case MAC:
                        w.write("\r");
                        break;
                }
            }
            w.flush();
            if (writeMode == WriteMode.PREPEND) {
                if (Files.isReadable(newPath)) {
                    Files.copy(newPath, os);
                }
            }
            if (writeBackup) {
                Files.move(newPath, newPath.resolveSibling(newPath.getFileName().toString() + "~"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(t, newPath, StandardCopyOption.REPLACE_EXISTING);
            if (writeMode == WriteMode.WRITE) {
                buffer.file = name;
                buffer.dirty = false;
            }
            setMessage("Wrote " + buffer.lines.size() + " lines");
            return true;
        } catch (IOException e) {
            setMessage("Error writing " + name + ": " + e.toString());
            return false;
        } finally {
            Files.deleteIfExists(t);
            writeMode = WriteMode.WRITE;
        }
    }

    private Operation getYNC(String message) {
        String oldEditMessage = editMessage;
        String oldEditBuffer = editBuffer.toString();
        LinkedHashMap<String, String> oldShortcuts = shortcuts;
        try {
            editMessage = message;
            editBuffer.setLength(0);
            KeyMap<Operation> yncKeyMap = new KeyMap<>();
            yncKeyMap.bind(Operation.YES, "y", "Y");
            yncKeyMap.bind(Operation.NO, "n", "N");
            yncKeyMap.bind(Operation.CANCEL, ctrl('C'));
            shortcuts = new LinkedHashMap<>();
            shortcuts.put(" Y", "Yes");
            shortcuts.put(" N", "No");
            shortcuts.put("^C", "Cancel");
            display();
            return readOperation(yncKeyMap);
        } finally {
            editMessage = oldEditMessage;
            editBuffer.append(oldEditBuffer);
            shortcuts = oldShortcuts;
        }
    }

    private String getWriteMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("File Name to ");
        switch (writeMode) {
            case WRITE:
                sb.append("Write");
                break;
            case APPEND:
                sb.append("Append");
                break;
            case PREPEND:
                sb.append("Prepend");
                break;
        }
        switch (buffer.format) {
            case UNIX:
                break;
            case DOS:
                sb.append(" [DOS Format]");
                break;
            case MAC:
                sb.append(" [Mac Format]");
                break;
        }
        if (writeBackup) {
            sb.append(" [Backup]");
        }
        sb.append(": ");
        return sb.toString();
    }

    void read() {
        KeyMap<Operation> readKeyMap = new KeyMap<>();
        readKeyMap.setUnicode(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            readKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        for (char i = 'A'; i <= 'Z'; i++) {
            readKeyMap.bind(Operation.DO_LOWER_CASE, alt(i));
        }
        readKeyMap.bind(Operation.BACKSPACE, del());
        readKeyMap.bind(Operation.NEW_BUFFER, alt('f'));
        readKeyMap.bind(Operation.TO_FILES, ctrl('T'));
        readKeyMap.bind(Operation.EXECUTE, ctrl('X'));
        readKeyMap.bind(Operation.ACCEPT, "\r");
        readKeyMap.bind(Operation.CANCEL, ctrl('C'));
        readKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        readKeyMap.bind(Operation.MOUSE_EVENT, key(terminal, Capability.key_mouse));
        readKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        readKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));

        editMessage = getReadMessage();
        editBuffer.setLength(0);
        int curPos = editBuffer.length();
        this.shortcuts = readShortcuts();
        display(curPos);
        while (true) {
            Operation op = readOperation(readKeyMap);
            switch (op) {
                case CANCEL:
                    editMessage = null;
                    this.shortcuts = standardShortcuts();
                    return;
                case ACCEPT:
                    editMessage = null;
                    String file = editBuffer.toString();
                    boolean empty = file.isEmpty();
                    Path p = empty ? null : root.resolve(file);
                    if (!readNewBuffer && !empty && !Files.exists(p)) {
                        setMessage("\"" + file + "\" not found");
                    } else if (!empty && Files.isDirectory(p)) {
                        setMessage("\"" + file + "\" is a directory");
                    } else if (!empty && !Files.isRegularFile(p)) {
                        setMessage("\"" + file + "\" is not a regular file");
                    } else {
                        Buffer buf = new Buffer(empty ? null : file);
                        try {
                            buf.open();
                            if (readNewBuffer) {
                                buffers.add(++bufferIndex, buf);
                                buffer = buf;
                            } else {
                                buffer.insert(String.join("\n", buf.lines));
                            }
                            setMessage(null);
                        } catch (IOException e) {
                            setMessage("Error reading " + file + ": " + e.getMessage());
                        }
                    }
                    this.shortcuts = standardShortcuts();
                    return;
                case HELP:
                    help("nano-read-help.txt");
                    break;
                case NEW_BUFFER:
                    readNewBuffer = !readNewBuffer;
                    break;
                case MOUSE_EVENT:
                    mouseEvent();
                    break;
                default:
                    curPos = editInputBuffer(op, curPos);
                    break;
            }
            editMessage = getReadMessage();
            display(curPos);
        }
    }

    private String getReadMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("File to insert");
        if (readNewBuffer) {
            sb.append(" into new buffer");
        }
        sb.append(" [from ./]: ");
        return sb.toString();
    }

    void gotoLine() throws IOException {
        KeyMap<Operation> readKeyMap = new KeyMap<>();
        readKeyMap.setUnicode(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            readKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        readKeyMap.bind(Operation.BACKSPACE, del());
        readKeyMap.bind(Operation.ACCEPT, "\r");
        readKeyMap.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        readKeyMap.bind(Operation.CANCEL, ctrl('C'));
        readKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        readKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));
        readKeyMap.bind(Operation.FIRST_LINE, ctrl('Y'));
        readKeyMap.bind(Operation.LAST_LINE, ctrl('V'));
        readKeyMap.bind(Operation.SEARCH, ctrl('T'));

        editMessage = "Enter line number, column number: ";
        editBuffer.setLength(0);
        int curPos = editBuffer.length();
        this.shortcuts = gotoShortcuts();
        display(curPos);
        while (true) {
            Operation op = readOperation(readKeyMap);
            switch (op) {
                case CANCEL:
                    editMessage = null;
                    this.shortcuts = standardShortcuts();
                    return;
                case FIRST_LINE:
                    editMessage = null;
                    buffer.firstLine();
                    this.shortcuts = standardShortcuts();
                    return;
                case LAST_LINE:
                    editMessage = null;
                    buffer.lastLine();
                    this.shortcuts = standardShortcuts();
                    return;
                case SEARCH:
                    search();
                    return;
                case ACCEPT:
                    editMessage = null;
                    String[] pos = editBuffer.toString().split(",", 2);
                    int[] args = { 0, 0 };
                    try {
                        for(int i = 0; i < pos.length; i++) {
                            if (pos[i].trim().length() > 0) {
                                args[i] = Integer.parseInt(pos[i]) - 1;
                                if (args[i] < 0) {
                                    throw new NumberFormatException();
                                }
                            }
                        }
                        buffer.gotoLine(args[1], args[0]);
                    } catch (NumberFormatException ex) {
                        setMessage("Invalid line or column number");
                    } catch (Exception ex) {
                        setMessage("Internal error: " + ex.getMessage());
                    }
                    this.shortcuts = standardShortcuts();
                    return;
                case HELP:
                    help("nano-goto-help.txt");
                    break;
                default:
                    curPos = editInputBuffer(op, curPos);
                    break;
            }
            display(curPos);
        }
    }

    private LinkedHashMap<String, String> gotoShortcuts() {
        LinkedHashMap<String, String> shortcuts = new LinkedHashMap<>();
        shortcuts.put("^G", "Get Help");
        shortcuts.put("^Y", "First Line");
        shortcuts.put("^T", "Go To Text");
        shortcuts.put("^C", "Cancel");
        shortcuts.put("^V", "Last Line");
        return shortcuts;
    }

    private LinkedHashMap<String, String> readShortcuts() {
        LinkedHashMap<String, String> shortcuts = new LinkedHashMap<>();
        shortcuts.put("^G", "Get Help");
        shortcuts.put("^T", "To Files");
        shortcuts.put("M-F", "New Buffer");
        shortcuts.put("^C", "Cancel");
        shortcuts.put("^X", "Execute Command");
        return shortcuts;
    }

    private LinkedHashMap<String, String> writeShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        s.put("M-M", "Mac Format");
        s.put("^C", "Cancel");
        s.put("M-D", "DOS Format");
        if (!restricted) {
            s.put("^T", "To Files");
            s.put("M-P", "Prepend");
            s.put("M-A", "Append");
            s.put("M-B", "Backup File");
        }
        return s;
    }

    private LinkedHashMap<String, String> helpShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^L", "Refresh");
        s.put("^Y", "Prev Page");
        s.put("^P", "Prev Line");
        s.put("M-\\", "First Line");
        s.put("^X", "Exit");
        s.put("^V", "Next Page");
        s.put("^N", "Next Line");
        s.put("M-/", "Last Line");
        return s;
    }

    private LinkedHashMap<String, String> searchShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        s.put("^Y", "First Line");
        s.put("^R", "Replace");
        s.put("^W", "Beg of Par");
        s.put("M-C", "Case Sens");
        s.put("M-R", "Regexp");
        s.put("^C", "Cancel");
        s.put("^V", "Last Line");
        s.put("^T", "Go To Line");
        s.put("^O", "End of Par");
        s.put("M-B", "Backwards");
        s.put("^P", "PrevHstory");
        return s;
    }

    private LinkedHashMap<String, String> standardShortcuts() {
        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("^G", "Get Help");
        s.put("^O", "WriteOut");
        s.put("^R", "Read File");
        s.put("^Y", "Prev Page");
        s.put("^K", "Cut Text");
        s.put("^C", "Cur Pos");
        s.put("^X", "Exit");
        s.put("^J", "Justify");
        s.put("^W", "Where Is");
        s.put("^V", "Next Page");
        s.put("^U", "UnCut Text");
        s.put("^T", "To Spell");
        return s;
    }

    void help(String help) {
        Buffer org = this.buffer;
        Buffer newBuf = new Buffer(null);
        try (InputStream is = getClass().getResourceAsStream(help)) {
            newBuf.open(is);
        } catch (IOException e) {
            setMessage("Unable to read help");
            return;
        }
        LinkedHashMap<String, String> oldShortcuts = this.shortcuts;
        this.shortcuts = helpShortcuts();
        boolean oldWrapping = this.wrapping;
        boolean oldPrintLineNumbers = this.printLineNumbers;
        boolean oldConstantCursor = this.constantCursor;
        this.wrapping = true;
        this.printLineNumbers = false;
        this.constantCursor = false;
        this.buffer = newBuf;
        try {
            this.message = null;
            terminal.puts(Capability.cursor_invisible);
            display();
            while (true) {
                switch (readOperation(keys)) {
                    case QUIT:
                        return;
                    case FIRST_LINE:
                        buffer.firstLine();
                        break;
                    case LAST_LINE:
                        buffer.lastLine();
                        break;
                    case PREV_PAGE:
                        buffer.prevPage();
                        break;
                    case NEXT_PAGE:
                        buffer.nextPage();
                        break;
                    case UP:
                        buffer.scrollUp(1);
                        break;
                    case DOWN:
                        buffer.scrollDown(1);
                        break;
                    case CLEAR_SCREEN:
                        clearScreen();
                        break;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    case TOGGLE_SUSPENSION:
                        toggleSuspension();
                        break;
               }
                display();
            }
        } finally {
            this.buffer = org;
            this.wrapping = oldWrapping;
            this.printLineNumbers = oldPrintLineNumbers;
            this.constantCursor = oldConstantCursor;
            this.shortcuts = oldShortcuts;
            terminal.puts(Capability.cursor_visible);
        }
    }

    void search() throws IOException {
        KeyMap<Operation> searchKeyMap = new KeyMap<>();
        searchKeyMap.setUnicode(Operation.INSERT);
//        searchKeyMap.setNomatch(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            searchKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        for (char i = 'A'; i <= 'Z'; i++) {
            searchKeyMap.bind(Operation.DO_LOWER_CASE, alt(i));
        }
        searchKeyMap.bind(Operation.BACKSPACE, del());
        searchKeyMap.bind(Operation.CASE_SENSITIVE, alt('c'));
        searchKeyMap.bind(Operation.BACKWARDS, alt('b'));
        searchKeyMap.bind(Operation.REGEXP, alt('r'));
        searchKeyMap.bind(Operation.ACCEPT, "\r");
        searchKeyMap.bind(Operation.CANCEL, ctrl('C'));
        searchKeyMap.bind(Operation.FIRST_LINE, ctrl('Y'));
        searchKeyMap.bind(Operation.LAST_LINE, ctrl('V'));
        searchKeyMap.bind(Operation.MOUSE_EVENT, key(terminal, Capability.key_mouse));
        searchKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        searchKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left));
        searchKeyMap.bind(Operation.UP, key(terminal, Capability.key_up));
        searchKeyMap.bind(Operation.DOWN, key(terminal, Capability.key_down));

        editMessage = getSearchMessage();
        editBuffer.setLength(0);
        String currentBuffer = "";
        int curPos = editBuffer.length();
        this.shortcuts = searchShortcuts();
        display(curPos);
        try {
            while (true) {
                Operation op = readOperation(searchKeyMap);
                switch (op) {
                    case UP:
                        searchTermId++;
                        if (searchTermId >= 0 && searchTermId < searchTerms.size()) {
                            if (searchTermId == 0) {
                                currentBuffer = editBuffer.toString();
                            }
                            editBuffer.setLength(0);
                            editBuffer.append(searchTerms.get(searchTermId));
                            curPos = editBuffer.length();
                        } else if (searchTermId >= searchTerms.size()) {
                            searchTermId = searchTerms.size() - 1;
                        }
                        break;
                    case DOWN:
                        if (searchTerms.size() > 0) {
                            searchTermId--;
                            editBuffer.setLength(0);
                            if (searchTermId < 0) {
                                searchTermId = -1;
                                editBuffer.append(currentBuffer);
                            } else {
                                editBuffer.append(searchTerms.get(searchTermId));
                            }
                            curPos = editBuffer.length();
                        }
                        break;
                    case CASE_SENSITIVE:
                        searchCaseSensitive = !searchCaseSensitive;
                        break;
                    case BACKWARDS:
                        searchBackwards = !searchBackwards;
                        break;
                    case REGEXP:
                        searchRegexp = !searchRegexp;
                        break;
                    case CANCEL:
                        return;
                    case ACCEPT:
                        if (editBuffer.length() > 0) {
                            searchTerm = editBuffer.toString();
                        }
                        if (searchTerm == null || searchTerm.isEmpty()) {
                            setMessage("Cancelled");
                        } else {
                            if (!searchTerms.contains(searchTerm)) {
                                searchTerms.add(searchTerm);
                            }
                            searchTermId = -1;
                            buffer.nextSearch();
                        }
                        return;
                    case HELP:
                        help("nano-search-help.txt");
                        break;
                    case FIRST_LINE:
                        buffer.firstLine();
                        return;
                    case LAST_LINE:
                        buffer.lastLine();
                        return;
                    case MOUSE_EVENT:
                        mouseEvent();
                        break;
                    case TOGGLE_SUSPENSION:
                        toggleSuspension();
                        break;
                    default:
                        curPos = editInputBuffer(op, curPos);
                        break;
               }
                editMessage = getSearchMessage();
                display(curPos);
            }
        } finally {
            this.shortcuts = standardShortcuts();
            editMessage = null;
        }
    }

    private String getSearchMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Search");
        if (searchCaseSensitive) {
            sb.append(" [Case Sensitive]");
        }
        if (searchRegexp) {
            sb.append(" [Regexp]");
        }
        if (searchBackwards) {
            sb.append(" [Backwards]");
        }
        if (searchTerm != null) {
            sb.append(" [");
            sb.append(searchTerm);
            sb.append("]");
        }
        sb.append(": ");
        return sb.toString();
    }

    String computeCurPos() {
        int chari = 0;
        int chart = 0;
        for (int i = 0; i < buffer.lines.size(); i++) {
            int l = buffer.lines.get(i).length() + 1;
            if (i < buffer.line) {
                chari += l;
            } else if (i == buffer.line) {
                chari += buffer.offsetInLine + buffer.column;
            }
            chart += l;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("line ");
        sb.append(buffer.line + 1);
        sb.append("/");
        sb.append(buffer.lines.size());
        sb.append(" (");
        sb.append(Math.round((100.0 * buffer.line) / buffer.lines.size()));
        sb.append("%), ");
        sb.append("col ");
        sb.append(buffer.offsetInLine + buffer.column + 1);
        sb.append("/");
        sb.append(buffer.lines.get(buffer.line).length() + 1);
        sb.append(" (");
        if (buffer.lines.get(buffer.line).length() > 0) {
            sb.append(Math.round((100.0 * (buffer.offsetInLine + buffer.column))
                    / (buffer.lines.get(buffer.line).length())));
        } else {
            sb.append("100");
        }
        sb.append("%), ");
        sb.append("char ");
        sb.append(chari + 1);
        sb.append("/");
        sb.append(chart);
        sb.append(" (");
        sb.append(Math.round((100.0 * chari) / chart));
        sb.append("%)");
        return sb.toString();
    }

    void curPos() {
        setMessage(computeCurPos());
    }

    void prevBuffer() throws IOException {
        if (buffers.size() > 1) {
            bufferIndex = (bufferIndex + buffers.size() - 1) % buffers.size();
            buffer = buffers.get(bufferIndex);
            setMessage("Switched to " + buffer.getTitle());
            buffer.open();
            display.clear();
        } else {
            setMessage("No more open file buffers");
        }
    }

    void nextBuffer() throws IOException {
        if (buffers.size() > 1) {
            bufferIndex = (bufferIndex + 1) % buffers.size();
            buffer = buffers.get(bufferIndex);
            setMessage("Switched to " + buffer.getTitle());
            buffer.open();
            display.clear();
        } else {
            setMessage("No more open file buffers");
        }
    }

    void setMessage(String message) {
        this.message = message;
        this.nbBindings = 25;
    }

    boolean quit() throws IOException {
        if (buffer.dirty) {
            Operation op = getYNC("Save modified buffer (ANSWERING \"No\" WILL DESTROY CHANGES) ? ");
            switch (op) {
                case CANCEL:
                    return false;
                case NO:
                    break;
                case YES:
                    if (!write()) {
                        return false;
                    }
            }
        }
        buffers.remove(bufferIndex);
        if (bufferIndex == buffers.size() && bufferIndex > 0) {
            bufferIndex = buffers.size() - 1;
        }
        if (buffers.isEmpty()) {
            buffer = null;
            return true;
        } else {
            buffer = buffers.get(bufferIndex);
            buffer.open();
            display.clear();
            setMessage("Switched to " + buffer.getTitle());
            return false;
        }
    }

    void numbers() {
        printLineNumbers = !printLineNumbers;
        resetDisplay();
        setMessage("Lines numbering " + (printLineNumbers ? "enabled" : "disabled"));
    }

    void smoothScrolling() {
        smoothScrolling = !smoothScrolling;
        setMessage("Smooth scrolling " + (smoothScrolling ? "enabled" : "disabled"));
    }

    void mouseSupport() throws IOException {
        mouseSupport = !mouseSupport;
        setMessage("Mouse support " + (mouseSupport ? "enabled" : "disabled"));
        terminal.trackMouse(mouseSupport ? Terminal.MouseTracking.Normal : Terminal.MouseTracking.Off);
    }

    void constantCursor() {
        constantCursor = !constantCursor;
        setMessage("Constant cursor position display " + (constantCursor ? "enabled" : "disabled"));
    }

    void oneMoreLine() {
        oneMoreLine = !oneMoreLine;
        setMessage("Use of one more line for editing " + (oneMoreLine ? "enabled" : "disabled"));
    }

    void wrap() {
        wrapping = !wrapping;
        buffer.computeAllOffsets();
        resetDisplay();
        setMessage("Lines wrapping " + (wrapping ? "enabled" : "disabled"));
    }

    void clearScreen() {
        resetDisplay();
    }

    void mouseEvent() {
        MouseEvent event = terminal.readMouseEvent();
        if (event.getModifiers().isEmpty() && event.getType() == MouseEvent.Type.Released
                && event.getButton() == MouseEvent.Button.Button1) {
            int x = event.getX();
            int y = event.getY();
            int hdr = buffer.computeHeader().size();
            int ftr = computeFooter().size();
            if (y < hdr) {
                // nothing
            } else if (y < size.getRows() - ftr) {
                buffer.moveTo(x, y - hdr);
            } else {
                int cols = (shortcuts.size() + 1) / 2;
                int cw = size.getColumns() / cols;
                int l = y - (size.getRows() - ftr) - 1;
                int si = l * cols +  x / cw;
                String shortcut = null;
                Iterator<String> it = shortcuts.keySet().iterator();
                while (si-- >= 0 && it.hasNext()) { shortcut = it.next(); }
                if (shortcut != null) {
                    shortcut = shortcut.replaceAll("M-", "\\\\E");
                    String seq = KeyMap.translate(shortcut);
                    bindingReader.runMacro(seq);
                }
            }
        }
        else if (event.getType() == MouseEvent.Type.Wheel) {
            if (event.getButton() == MouseEvent.Button.WheelDown) {
                buffer.moveDown(1);
            } else if (event.getButton() == MouseEvent.Button.WheelUp) {
                buffer.moveUp(1);
            }
        }
    }

    void toggleSuspension(){
        if (restricted) {
            setMessage("This function is disabled in restricted mode");
        } else if (vsusp < 0) {
            setMessage("This function is disabled");
        } else {
            Attributes attrs = terminal.getAttributes();
            int toggle = vsusp;
            String message = "enabled";
            if (attrs.getControlChar(ControlChar.VSUSP) > 0) {
                toggle = 0;
                message = "disabled";
            }
            attrs.setControlChar(ControlChar.VSUSP, toggle);
            terminal.setAttributes(attrs);
            setMessage("Suspension " + message);
        }
    }

    public String getTitle() {
        return title;
    }

    void resetDisplay() {
        display.clear();
        display.resize(size.getRows(), size.getColumns());
        for (Buffer buffer : buffers) {
            buffer.resetDisplay();
        }
    }

    synchronized void display() {
        display(null);
    }

    synchronized void display(final Integer editCursor) {
        if (nbBindings > 0) {
            if (--nbBindings == 0) {
                message = null;
            }
        }

        List<AttributedString> header = buffer.computeHeader();
        List<AttributedString> footer = computeFooter();

        int nbLines = size.getRows() - header.size() - footer.size();
        List<AttributedString> newLines = buffer.getDisplayedLines(nbLines);
        newLines.addAll(0, header);
        newLines.addAll(footer);

        // Compute cursor position
        int cursor;
        if (editMessage != null) {
            int crsr = editCursor != null ? editCursor : editBuffer.length();
            cursor = editMessage.length() + crsr;
            cursor = size.cursorPos(size.getRows() - footer.size(), cursor);
        } else {
            cursor = size.cursorPos(header.size(),
                                    buffer.getDisplayedCursor());
        }
        display.update(newLines, cursor);
    }

    protected List<AttributedString> computeFooter() {
        List<AttributedString> footer = new ArrayList<>();

        if (editMessage != null) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(AttributedStyle.INVERSE);
            sb.append(editMessage);
            sb.append(editBuffer);
            for (int i = editMessage.length() + editBuffer.length(); i < size.getColumns(); i++) {
                sb.append(' ');
            }
            sb.append('\n');
            footer.add(sb.toAttributedString());
        } else if (message!= null || constantCursor) {
            int rwidth = size.getColumns();
            String text = "[ " + (message == null ? computeCurPos() : message) + " ]";
            int len = text.length();
            AttributedStringBuilder sb = new AttributedStringBuilder();
            for (int i = 0; i < (rwidth - len) / 2; i++) {
                sb.append(' ');
            }
            sb.style(AttributedStyle.INVERSE);
            sb.append(text);
            sb.append('\n');
            footer.add(sb.toAttributedString());
        } else {
            footer.add(new AttributedString("\n"));
        }

        Iterator<Entry<String, String>> sit = shortcuts.entrySet().iterator();
        int cols = (shortcuts.size() + 1) / 2;
        int cw = (size.getColumns() - 1) / cols;
        int rem = (size.getColumns() - 1) % cols;
        for (int l = 0; l < 2; l++) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            for (int c = 0; c < cols; c++) {
                Map.Entry<String, String> entry = sit.hasNext() ? sit.next() : null;
                String key = entry != null ? entry.getKey() : "";
                String val = entry != null ? entry.getValue() : "";
                sb.style(AttributedStyle.INVERSE);
                sb.append(key);
                sb.style(AttributedStyle.DEFAULT);
                sb.append(" ");
                int nb = cw - key.length() - 1 + (c < rem ? 1 : 0);
                if (val.length() > nb) {
                    sb.append(val.substring(0, nb));
                } else {
                    sb.append(val);
                    if (c < cols - 1) {
                        for (int i = 0; i < nb - val.length(); i++) {
                            sb.append(" ");
                        }
                    }
                }
            }
            sb.append('\n');
            footer.add(sb.toAttributedString());
        }

        return footer;
    }

    protected void handle(Signal signal) {
        if (buffer != null) {
            size.copy(terminal.getSize());
            buffer.computeAllOffsets();
            buffer.moveToChar(buffer.offsetInLine + buffer.column);
            resetDisplay();
            display();
        }
    }

    protected void bindKeys() {
        keys = new KeyMap<>();
        keys.setUnicode(Operation.INSERT);

        for (char i = 32; i < KEYMAP_LENGTH; i++) {
            keys.bind(Operation.INSERT, Character.toString(i));
        }
        keys.bind(Operation.BACKSPACE, del());
        for (char i = 'A'; i <= 'Z'; i++) {
            keys.bind(Operation.DO_LOWER_CASE, alt(i));
        }

        keys.bind(Operation.HELP, ctrl('G'), key(terminal, Capability.key_f1));
        keys.bind(Operation.QUIT, ctrl('X'), key(terminal, Capability.key_f2));
        keys.bind(Operation.WRITE, ctrl('O'), key(terminal, Capability.key_f3));
        keys.bind(Operation.JUSTIFY_PARAGRAPH, ctrl('J'), key(terminal, Capability.key_f4));

        keys.bind(Operation.READ, ctrl('R'), key(terminal, Capability.key_f5));
        keys.bind(Operation.SEARCH, ctrl('W'), key(terminal, Capability.key_f6));
        keys.bind(Operation.PREV_PAGE, ctrl('Y'), key(terminal, Capability.key_f7));
        keys.bind(Operation.NEXT_PAGE, ctrl('V'), key(terminal, Capability.key_f8));

        keys.bind(Operation.CUT, ctrl('K'), key(terminal, Capability.key_f9));
        keys.bind(Operation.UNCUT, ctrl('U'), key(terminal, Capability.key_f10));
        keys.bind(Operation.CUR_POS, ctrl('C'), key(terminal, Capability.key_f11));
        keys.bind(Operation.TO_SPELL, ctrl('T'), key(terminal, Capability.key_f11));

        keys.bind(Operation.GOTO, ctrl('_'), key(terminal, Capability.key_f13), alt('g'));
        keys.bind(Operation.REPLACE, ctrl('\\'), key(terminal, Capability.key_f14), alt('r'));
        keys.bind(Operation.MARK, ctrl('^'), key(terminal, Capability.key_f15), alt('a'));
        keys.bind(Operation.NEXT_SEARCH, key(terminal, Capability.key_f16), alt('w'));

        keys.bind(Operation.COPY, alt('^'), alt('6'));
        keys.bind(Operation.INDENT, alt('}'));
        keys.bind(Operation.UNINDENT, alt('{'));

        keys.bind(Operation.RIGHT, ctrl('F'));
        keys.bind(Operation.LEFT, ctrl('B'));
        keys.bind(Operation.NEXT_WORD, ctrl(' '));
        keys.bind(Operation.PREV_WORD, alt(' '));
        keys.bind(Operation.UP, ctrl('P'));
        keys.bind(Operation.DOWN, ctrl('N'));

        keys.bind(Operation.BEGINNING_OF_LINE, ctrl('A'), key(terminal, Capability.key_home));
        keys.bind(Operation.END_OF_LINE, ctrl('E'), key(terminal, Capability.key_end));
        keys.bind(Operation.BEGINNING_OF_PARAGRAPH, alt('('), alt('9'));
        keys.bind(Operation.END_OF_PARAGRAPH, alt(')'), alt('0'));
        keys.bind(Operation.FIRST_LINE, alt('\\'), alt('|'));
        keys.bind(Operation.LAST_LINE, alt('/'), alt('?'));

        keys.bind(Operation.MATCHING, alt(']'));
        keys.bind(Operation.SCROLL_UP, alt('-'), alt('_'));
        keys.bind(Operation.SCROLL_DOWN, alt('+'), alt('='));

        keys.bind(Operation.PREV_BUFFER, alt('<'));
        keys.bind(Operation.NEXT_BUFFER, alt('>'));
        keys.bind(Operation.PREV_BUFFER, alt(','));
        keys.bind(Operation.NEXT_BUFFER, alt('.'));

        keys.bind(Operation.VERBATIM, alt('v'));
        keys.bind(Operation.INSERT, ctrl('I'), ctrl('M'));
        keys.bind(Operation.DELETE, ctrl('D'));
        keys.bind(Operation.BACKSPACE, ctrl('H'));
        keys.bind(Operation.CUT_TO_END, alt('t'));

        keys.bind(Operation.JUSTIFY_FILE, alt('j'));
        keys.bind(Operation.COUNT, alt('d'));
        keys.bind(Operation.CLEAR_SCREEN, ctrl('L'));

        keys.bind(Operation.HELP, alt('x'));
        keys.bind(Operation.CONSTANT_CURSOR, alt('c'));
        keys.bind(Operation.ONE_MORE_LINE, alt('o'));
        keys.bind(Operation.SMOOTH_SCROLLING, alt('s'));
        keys.bind(Operation.MOUSE_SUPPORT, alt('m'));
        keys.bind(Operation.WHITESPACE, alt('p'));
        keys.bind(Operation.HIGHLIGHT, alt('y'));

        keys.bind(Operation.SMART_HOME_KEY, alt('h'));
        keys.bind(Operation.AUTO_INDENT, alt('i'));
        keys.bind(Operation.CUT_TO_END_TOGGLE, alt('k'));
        keys.bind(Operation.WRAP, alt('l'));
        keys.bind(Operation.TABS_TO_SPACE, alt('q'));

        keys.bind(Operation.BACKUP, alt('b'));

        keys.bind(Operation.NUMBERS, alt('n'));

        // TODO: map other keys
        keys.bind(Operation.UP, key(terminal, Capability.key_up));
        keys.bind(Operation.DOWN, key(terminal, Capability.key_down));
        keys.bind(Operation.RIGHT, key(terminal, Capability.key_right));
        keys.bind(Operation.LEFT, key(terminal, Capability.key_left));
        keys.bind(Operation.MOUSE_EVENT, key(terminal, Capability.key_mouse));
        keys.bind(Operation.TOGGLE_SUSPENSION, alt('z'));
    }

    protected enum Operation {
        DO_LOWER_CASE,

        QUIT,
        WRITE,
        READ,
        GOTO,
        FIND,

        WRAP,
        NUMBERS,
        SMOOTH_SCROLLING,
        MOUSE_SUPPORT,
        ONE_MORE_LINE,
        CLEAR_SCREEN,

        UP,
        DOWN,
        LEFT,
        RIGHT,

        INSERT,
        BACKSPACE,

        NEXT_BUFFER,
        PREV_BUFFER,

        HELP,
        NEXT_PAGE,
        PREV_PAGE,
        SCROLL_UP,
        SCROLL_DOWN,
        NEXT_WORD,
        PREV_WORD,
        BEGINNING_OF_LINE,
        END_OF_LINE,
        FIRST_LINE,
        LAST_LINE,

        CUR_POS,

        CASE_SENSITIVE,
        BACKWARDS,
        REGEXP,
        ACCEPT,
        CANCEL,
        SEARCH,
        MAC_FORMAT,
        DOS_FORMAT,
        APPEND_MODE,
        PREPEND_MODE,
        BACKUP,
        TO_FILES,
        YES,
        NO,
        NEW_BUFFER,
        EXECUTE,
        NEXT_SEARCH,
        MATCHING,
        VERBATIM,
        DELETE,

        JUSTIFY_PARAGRAPH,
        TO_SPELL,
        CUT,
        REPLACE,
        MARK,
        COPY,
        INDENT,
        UNINDENT,
        BEGINNING_OF_PARAGRAPH,
        END_OF_PARAGRAPH,
        CUT_TO_END,
        JUSTIFY_FILE,
        COUNT,
        CONSTANT_CURSOR,
        WHITESPACE,
        HIGHLIGHT,
        SMART_HOME_KEY,
        AUTO_INDENT,
        CUT_TO_END_TOGGLE,
        TABS_TO_SPACE,
        UNCUT,

        MOUSE_EVENT,

        TOGGLE_SUSPENSION
    }

}
