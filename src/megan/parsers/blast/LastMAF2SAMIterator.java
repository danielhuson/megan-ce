/*
 *  Copyright (C) 2015 Daniel H. Huson
 *
 *  (Some files contain contributions from other authors, who are then mentioned separately.)
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package megan.parsers.blast;

import jloda.util.Basic;
import megan.util.LastMAFFileFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;


/**
 * parses a LAST MAF files into SAM format
 * Daniel Huson, 2.2017
 */
public class LastMAF2SAMIterator extends SAMIteratorBase implements ISAMIterator {
    public final static String NEW_QUERY = "Query=";
    public final static String NEW_MATCH = ">";
    public final static String QUERY = "Query";
    public final static String SUBJECT = "Sbjct";
    public final static String SCORE = "Score";
    public final static String EXPECT = "Expect";
    public final static String LENGTH = "Length";
    public final static String IDENTITIES = "Identities";
    public final static String FRAME = "Frame";
    public final static String EQUALS = "=";

    private byte[] matchesText = new byte[10000];
    private int matchesTextLength = 0;

    private final BlastMode blastMode;

    private final ArrayList<String> refHeaderLines = new ArrayList<>(1000);

    private TreeSet<Match> matches = new TreeSet<>(new Match());

    private long numberOfReads = 0;

    private double lambda = -1;
    private double K = -1;

    private final String[] mafMatch = new String[3];

    /**
     * constructor
     *
     * @param fileName
     * @throws IOException
     */
    protected LastMAF2SAMIterator(String fileName, int maxNumberOfMatchesPerRead, BlastMode blastMode) throws IOException {
        super(fileName, maxNumberOfMatchesPerRead);
        this.blastMode = blastMode;
        if (!LastMAFFileFilter.getInstance().accept(fileName)) {
            close();
            throw new IOException("File not a LAST file in MAF format: " + fileName);
        }

        while (hasNextLine()) {
            String line = nextLine();
            String str = getNextToken(line, "lambda=");
            if (Basic.isDouble(str)) {
                lambda = Basic.parseDouble(str);
                str = getNextToken(line, "K=");
                K = Basic.parseDouble(str);
                break;
            }
        }
        if (lambda == -1 || K == -1)
            throw new IOException("Failed to parse lambda and K");

        moveToNextMAFMatch();
    }

    /**
     * is there more data?
     *
     * @return true, if more data available
     */
    @Override
    public boolean hasNext() {
        return mafMatch[0] != null;
    }

    /**
     * gets the next matches
     *
     * @return number of matches
     */
    public int next() {
        matchesTextLength = 0;

        if (mafMatch[0] == null)
            return -1; // at end of file

        final String firstQueryName;
        {
            numberOfReads++;
            firstQueryName = getNextToken(mafMatch[2], "s").trim();
        }

        int matchId = 0; // used to distinguish between matches when sorting
        matches.clear();

        // get all matches for given query:
        try {
            while (true) {
                if (mafMatch[2] != null && getNextToken(mafMatch[2], "s").trim().equals(firstQueryName)) {
                    final String[] queryTokens = Basic.splitOnWhiteSpace(mafMatch[2]);
                    /*
                        a score=159 EG2=1e-08 E=4.3e-17
                        s WP_005682092.1                       18 33 + 516 SAEANENERRWNDDKIDRKNQDSTNNYDKTRMK
                        s HISEQ:457:C5366ACXX:2:1101:2641:2226  1 99 + 100 TAEANENERHWNDDKIERKNQDPTNHYDKSRMR
                     */
                    final String queryAligned = queryTokens[6];
                    int queryStart = Basic.parseInt(queryTokens[2]) + 1;
                    boolean queryReversed = !queryTokens[4].equals("+");
                    int queryAlignmentLength = Basic.parseInt(queryTokens[3]);
                    int queryEnd;
                    if (queryReversed) {
                        queryEnd = queryStart;
                        queryStart = queryStart + queryAlignmentLength - 1;
                    } else {
                        queryEnd = queryStart + queryAlignmentLength - 1;
                    }

                    final int frame;
                    if (blastMode == BlastMode.BlastX) {
                        if (queryReversed) {
                            int queryLength = Basic.parseInt(queryTokens[5]);
                            frame = -((queryLength - queryStart) % 3 + 1);
                        } else
                            frame = ((queryStart - 1) % 3 + 1);
                    } else
                        frame = 0;

                    final String scoreLine = mafMatch[0];
                    final int rawScore = Basic.parseInt(getNextToken(scoreLine, "score="));
                    final double expect = Basic.parseDouble(getNextToken(scoreLine, "E="));
                    final float bitScore = (float) ((lambda * rawScore - Math.log(K)) / Math.log(2));

                    final String[] subjTokens = Basic.splitOnWhiteSpace(mafMatch[1]);

                    final String subjName = subjTokens[1];
                    final String subjAligned = subjTokens[6];
                    int subjStart = Basic.parseInt(subjTokens[2]) + 1;
                    int subjAlignmentLength = Basic.parseInt(subjTokens[3]);
                    boolean subjReversed = !subjTokens[4].equals("+");
                    int subjEnd;
                    if (!subjReversed) {
                        subjEnd = subjStart + subjAlignmentLength - 1;
                    } else {
                        subjEnd = subjStart;
                        subjStart = subjStart + subjAlignmentLength - 1;
                    }

                    final float percentIdentities;
                    {
                        final int nCompared = Math.min(queryAligned.length(), subjAligned.length());
                        if (nCompared > 0) {
                            int same = 0;
                            for (int i = 0; i < nCompared; i++)
                                if (queryAligned.charAt(i) == subjAligned.charAt(i))
                                    same++;
                            percentIdentities = (float) same / (float) nCompared;
                        } else
                            percentIdentities = 0;
                    }
                    int subjLength = Basic.parseInt(subjTokens[5]);

                    if (matches.size() < getMaxNumberOfMatchesPerRead() || bitScore > matches.last().bitScore) {
                        Match match = new Match();
                        match.bitScore = bitScore;
                        match.id = matchId++;
                        if (blastMode == BlastMode.BlastN)
                            match.samLine = BlastN2SAMIterator.makeSAM(firstQueryName, queryReversed ? "Minus" : "Plus", subjName, subjLength, subjReversed ? "Minus" : "Plus", bitScore, (float) expect, rawScore, percentIdentities, queryStart, queryEnd, subjStart, subjEnd, queryAligned, subjAligned);
                        else
                            match.samLine = BlastX2SAMIterator.makeSAM(firstQueryName, subjName, subjLength, bitScore, (float) expect, rawScore, percentIdentities, frame, queryStart, queryEnd, subjStart, subjEnd, queryAligned, subjAligned);
                        matches.add(match);
                        if (matches.size() > getMaxNumberOfMatchesPerRead())
                            matches.remove(matches.last());
                    }
                    moveToNextMAFMatch();
                } else // new query or last alignment, in either case return
                {
                    break;
                }
            }

        } catch (Exception ex) {
            System.err.println("Error parsing file near line: " + getLineNumber() + ": " + ex.getMessage());
            if (incrementNumberOfErrors() >= getMaxNumberOfErrors())
                throw new RuntimeException("Too many errors");
        }

        if (matches.size() == 0) { // no matches, so return query name only
            if (firstQueryName.length() > matchesText.length) {
                matchesText = new byte[2 * firstQueryName.length()];
            }
            for (int i = 0; i < firstQueryName.length(); i++)
                matchesText[matchesTextLength++] = (byte) firstQueryName.charAt(i);
            matchesText[matchesTextLength++] = '\n';
            return 0;
        } else {
            for (Match match : matches) {
                byte[] bytes = match.samLine.getBytes();
                if (matchesTextLength + bytes.length + 1 >= matchesText.length) {
                    byte[] tmp = new byte[2 * (matchesTextLength + bytes.length + 1)];
                    System.arraycopy(matchesText, 0, tmp, 0, matchesTextLength);
                    matchesText = tmp;
                }
                System.arraycopy(bytes, 0, matchesText, matchesTextLength, bytes.length);
                matchesTextLength += bytes.length;
                matchesText[matchesTextLength++] = '\n';
            }
            return matches.size();
        }
    }

    /**
     * move to the next MAF match
     */
    private boolean moveToNextMAFMatch() {
        mafMatch[0] = getNextLineStartsWith("a ");
        if (mafMatch[0] != null) {
            mafMatch[1] = getNextLineStartsWith("s ");
            if (mafMatch[1] != null)
                mafMatch[2] = getNextLineStartsWith("s ");
        }

        if (mafMatch[0] == null || mafMatch[1] == null || mafMatch[2] == null) {
            mafMatch[0] = mafMatch[1] = mafMatch[2] = null;
            return false;
        }
        return true;
    }

    /**
     * gets the matches text
     *
     * @return matches text
     */
    @Override
    public byte[] getMatchesText() {
        return matchesText;
    }

    /**
     * length of matches text
     *
     * @return length of text
     */
    @Override
    public int getMatchesTextLength() {
        return matchesTextLength;
    }
}