/*
 * BLAST2RMA6.java Copyright (C) 2020. Daniel H. Huson
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
 *
 */
package megan.tools;

import jloda.swing.commands.CommandManager;
import jloda.swing.util.ArgsOptions;
import jloda.swing.util.ResourceManager;
import jloda.util.*;
import megan.accessiondb.AccessAccessionMappingDatabase;
import megan.classification.Classification;
import megan.classification.ClassificationManager;
import megan.classification.IdMapper;
import megan.classification.IdParser;
import megan.classification.data.ClassificationCommandHelper;
import megan.core.ContaminantManager;
import megan.core.Document;
import megan.core.SampleAttributeTable;
import megan.main.Megan6;
import megan.main.MeganProperties;
import megan.parsers.blast.BlastFileFormat;
import megan.parsers.blast.BlastModeUtils;
import megan.rma6.RMA6Connector;
import megan.rma6.RMA6FromBlastCreator;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * compute an RMA6 file from a SAM file generated by DIAMOND or MALT
 * Daniel Huson, 3.2012
 */
public class BLAST2RMA6 {
    /**
     * merge RMA files
     *
     * @param args
     * @throws UsageException
     * @throws IOException
     */
    public static void main(String[] args) {
        try {
            ResourceManager.addResourceRoot(Megan6.class, "megan.resources");
            ProgramProperties.setProgramName("Blast2RMA");
            ProgramProperties.setProgramVersion(megan.main.Version.SHORT_DESCRIPTION);

            PeakMemoryUsageMonitor.start();
            (new BLAST2RMA6()).run(args);
            System.err.println("Total time:  " + PeakMemoryUsageMonitor.getSecondsSinceStartString());
            System.err.println("Peak memory: " + PeakMemoryUsageMonitor.getPeakUsageString());
            System.exit(0);
        } catch (Exception ex) {
            Basic.caught(ex);
            System.exit(1);
        }
    }

    /**
     * run
     *
     * @param args
     * @throws UsageException
     * @throws IOException
     * @throws ClassNotFoundException
     */
    private void run(String[] args) throws UsageException, IOException, ClassNotFoundException, CanceledException, SQLException {
        CommandManager.getGlobalCommands().addAll(ClassificationCommandHelper.getGlobalCommands());

        final ArgsOptions options = new ArgsOptions(args, this, "Computes MEGAN RMA files from  BLAST (or similar) files");
        options.setVersion(ProgramProperties.getProgramVersion());
        options.setLicense("Copyright (C) 2020 Daniel H. Huson. This program comes with ABSOLUTELY NO WARRANTY.");
        options.setAuthors("Daniel H. Huson");

        options.comment("Input");
        final String[] blastFiles = options.getOptionMandatory("-i", "in", "Input BLAST file[s] (gzipped ok)", new String[0]);
        final BlastFileFormat blastFormat = BlastFileFormat.valueOfIgnoreCase(options.getOptionMandatory("-f", "format", "Input file format", BlastFileFormat.values(), BlastFileFormat.Unknown.toString()));
        final BlastMode blastMode = BlastMode.valueOfIgnoreCase(options.getOption("-bm", "blastMode", "Blast mode", BlastMode.values(), BlastMode.Unknown.toString()));
        String[] readsFiles = options.getOption("-r", "reads", "Reads file(s) (fasta or fastq, gzipped ok)", new String[0]);
        final String[] metaDataFiles = options.getOption("-mdf", "metaDataFile", "Files containing metadata to be included in RMA6 files", new String[0]);

        options.comment("Output");
        String[] outputFiles = options.getOptionMandatory("-o", "out", "Output file(s), one for each input file, or a directory", new String[0]);
        boolean useCompression = options.getOption("-c", "useCompression", "Compress reads and matches in RMA file (smaller files, longer to generate", true);
        options.comment("Reads");
        final boolean pairedReads = options.getOption("-p", "paired", "Reads are paired", false);
        final int pairedReadsSuffixLength = options.getOption("-ps", "pairedSuffixLength", "Length of name suffix used to distinguish between name of read and its mate", 0);
        final boolean pairsInSingleFile = options.getOption("-pof", "pairedReadsInOneFile", "Are paired reads in one file (usually they are in two)", false);
        options.comment("Parameters");
        final boolean longReads = options.getOption("-lg", "longReads", "Parse and analyse as long reads", Document.DEFAULT_LONG_READS);

        final int maxMatchesPerRead = options.getOption("-m", "maxMatchesPerRead", "Max matches per read", 100);
        final boolean runClassifications = options.getOption("-class", "classify", "Run classification algorithm", true);
        final float minScore = options.getOption("-ms", "minScore", "Min score", Document.DEFAULT_MINSCORE);
        final float maxExpected = options.getOption("-me", "maxExpected", "Max expected", Document.DEFAULT_MAXEXPECTED);
        final float minPercentIdentity = options.getOption("-mpi", "minPercentIdentity", "Min percent identity", Document.DEFAULT_MIN_PERCENT_IDENTITY);
        final float topPercent = options.getOption("-top", "topPercent", "Top percent", Document.DEFAULT_TOPPERCENT);
        final int minSupport;
        final float minSupportPercent;
        {
            final float minSupportPercent0 = options.getOption("-supp", "minSupportPercent", "Min support as percent of assigned reads (0==off)", Document.DEFAULT_MINSUPPORT_PERCENT);
            final int minSupport0 = options.getOption("-sup", "minSupport", "Min support (0==off)", Document.DEFAULT_MINSUPPORT);
            if (minSupportPercent0 != Document.DEFAULT_MINSUPPORT_PERCENT && minSupport0 == Document.DEFAULT_MINSUPPORT) {
                minSupportPercent = minSupportPercent0;
                minSupport = 0;
            } else if (minSupportPercent0 == Document.DEFAULT_MINSUPPORT_PERCENT && minSupport0 != Document.DEFAULT_MINSUPPORT) {
                minSupportPercent = 0;
                minSupport = minSupport0;
            } else if (minSupportPercent0 != Document.DEFAULT_MINSUPPORT_PERCENT && minSupport0 != Document.DEFAULT_MINSUPPORT) {
                throw new IOException("Please specify a value for either --minSupport or --minSupportPercent, but not for both");
            } else {
                minSupportPercent = minSupportPercent0;
                minSupport = minSupport0;
            }
        }
        final float minPercentReadToCover = options.getOption("-mrc", "minPercentReadCover", "Min percent of read length to be covered by alignments", Document.DEFAULT_MIN_PERCENT_READ_TO_COVER);
        final float minPercentReferenceToCover = options.getOption("-mrefc", "minPercentReferenceCover", "Min percent of reference length to be covered by alignments", Document.DEFAULT_MIN_PERCENT_REFERENCE_TO_COVER);

        final Document.LCAAlgorithm lcaAlgorithm = Document.LCAAlgorithm.valueOfIgnoreCase(options.getOption("-alg", "lcaAlgorithm", "Set the LCA algorithm to use for taxonomic assignment",
                Document.LCAAlgorithm.values(), longReads ? Document.DEFAULT_LCA_ALGORITHM_LONG_READS.toString() : Document.DEFAULT_LCA_ALGORITHM_SHORT_READS.toString()));

        final float lcaCoveragePercent = options.getOption("-lcp", "lcaCoveragePercent", "Set the percent for the LCA to cover",
                lcaAlgorithm== Document.LCAAlgorithm.longReads? Document.DEFAULT_LCA_COVERAGE_PERCENT_LONG_READS : (lcaAlgorithm== Document.LCAAlgorithm.weighted?Document.DEFAULT_LCA_COVERAGE_PERCENT_WEIGHTED_LCA:Document.DEFAULT_LCA_COVERAGE_PERCENT_SHORT_READS));

        final Document.ReadAssignmentMode readAssignmentMode = Document.ReadAssignmentMode.valueOfIgnoreCase(options.getOption("-ram", "readAssignmentMode", "Set the read assignment mode",
                Document.ReadAssignmentMode.values(), longReads ? Document.DEFAULT_READ_ASSIGNMENT_MODE_LONG_READS.toString() : Document.DEFAULT_READ_ASSIGNMENT_MODE_SHORT_READS.toString()));

        final String contaminantsFile = options.getOption("-cf", "conFile", "File of contaminant taxa (one Id or name per line)", "");

        options.comment("Classification support:");

        final String mapDBFile = options.getOption("-mdb", "mapDB", "MEGAN mapping db (file megan-map.db)", "");

        options.comment("Deprecated classification support options:");

        final boolean parseTaxonNames = options.getOption("-tn", "parseTaxonNames", "Parse taxon names", true);
        final String acc2TaxaFile = options.getOption("-a2t", "acc2taxa", "Accessopm-to-Taxonomy mapping file", "");
        final String synonyms2TaxaFile = options.getOption("-s2t", "syn2taxa", "Synonyms-to-Taxonomy mapping file", "");

        final HashMap<String, String> class2AccessionFile = new HashMap<>();
        final HashMap<String, String> class2SynonymsFile = new HashMap<>();

        for (String cName : ClassificationManager.getAllSupportedClassificationsExcludingNCBITaxonomy()) {
            class2AccessionFile.put(cName, options.getOption("-a2" + cName.toLowerCase(), "acc2" + cName.toLowerCase(), "Accession-to-" + cName + " mapping file", ""));
            class2SynonymsFile.put(cName, options.getOption("-s2" + cName.toLowerCase(), "syn2" + cName.toLowerCase(), "Synonyms-to-" + cName + " mapping file", ""));
            final String tags = options.getOption("-t4" + cName.toLowerCase(), "tags4" + cName.toLowerCase(), "Tags for " + cName + " id parsing (must set to activate id parsing)", "").trim();
            if (tags.length() > 0)
                ProgramProperties.put(cName + "Tags", tags);
            ProgramProperties.put(cName + "ParseIds", tags.length() > 0);
        }

        options.comment(ArgsOptions.OTHER);
        ProgramProperties.put(IdParser.PROPERTIES_FIRST_WORD_IS_ACCESSION, options.getOption("-fwa", "firstWordIsAccession", "First word in reference header is accession number (set to 'true' for NCBI-nr downloaded Sep 2016 or later)", true));
        ProgramProperties.put(IdParser.PROPERTIES_ACCESSION_TAGS, options.getOption("-atags", "accessionTags", "List of accession tags", ProgramProperties.get(IdParser.PROPERTIES_ACCESSION_TAGS, IdParser.ACCESSION_TAGS)));

        options.done();

        final String propertiesFile;
        if (ProgramProperties.isMacOS())
            propertiesFile = System.getProperty("user.home") + "/Library/Preferences/Megan.def";
        else
            propertiesFile = System.getProperty("user.home") + File.separator + ".Megan.def";
        MeganProperties.initializeProperties(propertiesFile);


        for (String fileName : metaDataFiles) {
            Basic.checkFileReadableNonEmpty(fileName);
        }

        for (String fileName : readsFiles) {
            Basic.checkFileReadableNonEmpty(fileName);
        }

        final Collection<String> mapDBClassifications = AccessAccessionMappingDatabase.getContainedClassificationsIfDBExists(mapDBFile);
        if (mapDBClassifications.size() > 0 && (Basic.hasPositiveLengthValue(class2AccessionFile) || Basic.hasPositiveLengthValue(class2SynonymsFile)))
            throw new UsageException("Illegal to use both --mapDB and ---acc2... or --syn2... options");

        if (mapDBClassifications.size() > 0)
            ClassificationManager.setMeganMapDBFile(mapDBFile);

        final ArrayList<String> cNames = new ArrayList<>();
        for (String cName : ClassificationManager.getAllSupportedClassificationsExcludingNCBITaxonomy()) {
            if (mapDBClassifications.contains(cName) || class2AccessionFile.get(cName).length() > 0 || class2SynonymsFile.get(cName).length() > 0)
                cNames.add(cName);
        }
        if (cNames.size() > 0)
            System.err.println("Functional classifications to use: " + Basic.toString(cNames, ", "));


        final boolean processInPairs = (pairedReads && !pairsInSingleFile);

        if (outputFiles.length == 1) {
            if (blastFiles.length == 1 || (processInPairs && blastFiles.length == 2)) {
                if ((new File(outputFiles[0]).isDirectory()))
                    outputFiles[0] = (new File(outputFiles[0], Basic.replaceFileSuffix(Basic.getFileNameWithoutPath(Basic.getFileNameWithoutZipOrGZipSuffix(blastFiles[0])), ".rma6"))).getPath();
            } else if (blastFiles.length > 1) {
                if (!(new File(outputFiles[0]).isDirectory()))
                    throw new IOException("Multiple files given, but given single output is not a directory");
                String outputDirectory = (new File(outputFiles[0])).getParent();
                if (!processInPairs) {
                    outputFiles = new String[blastFiles.length];
                    for (int i = 0; i < blastFiles.length; i++)
                        outputFiles[i] = new File(outputDirectory, Basic.replaceFileSuffix(Basic.getFileNameWithoutZipOrGZipSuffix(Basic.getFileNameWithoutPath(blastFiles[i])), ".rma6")).getPath();
                } else {
                    outputFiles = new String[blastFiles.length / 2];
                    for (int i = 0; i < blastFiles.length; i += 2)
                        outputFiles[i / 2] = new File(outputDirectory, Basic.replaceFileSuffix(Basic.getFileNameWithoutZipOrGZipSuffix(Basic.getFileNameWithoutPath(blastFiles[i])), ".rma6")).getPath();
                }
            }
        } else // output.length >1
        {
            if ((!processInPairs && blastFiles.length != outputFiles.length) || (processInPairs && blastFiles.length != 2 * outputFiles.length))
                throw new IOException("Number of input and output files do not match");
        }

        if (metaDataFiles.length > 1 && metaDataFiles.length != outputFiles.length) {
            throw new IOException("Number of metadata files (" + metaDataFiles.length + ") doesn't match number of output files (" + outputFiles.length + ")");
        }

        if (readsFiles.length == 0) {
            readsFiles = new String[blastFiles.length];
            Arrays.fill(readsFiles, "");
        } else if (readsFiles.length != blastFiles.length)
            throw new IOException("Number of reads files must equal number of BLAST files");

        final IdMapper taxonIdMapper = ClassificationManager.get(Classification.Taxonomy, true).getIdMapper();
        final IdMapper[] idMappers = new IdMapper[cNames.size()];

        // Load all mapping files:
        if (runClassifications) {
            ClassificationManager.get(Classification.Taxonomy, true);
            taxonIdMapper.setUseTextParsing(parseTaxonNames);

            if (mapDBFile.length() > 0) {
                taxonIdMapper.loadMappingFile(mapDBFile, IdMapper.MapType.MeganMapDB, false, new ProgressPercentage());
            }
            if (acc2TaxaFile.length() > 0) {
                taxonIdMapper.loadMappingFile(acc2TaxaFile, IdMapper.MapType.Accession, false, new ProgressPercentage());
            }
            if (synonyms2TaxaFile.length() > 0) {
                taxonIdMapper.loadMappingFile(synonyms2TaxaFile, IdMapper.MapType.Synonyms, false, new ProgressPercentage());
            }

            for (int i = 0; i < cNames.size(); i++) {
                final String cName = cNames.get(i);

                idMappers[i] = ClassificationManager.get(cName, true).getIdMapper();

                if (mapDBClassifications.contains(cName))
                    idMappers[i].loadMappingFile(mapDBFile, IdMapper.MapType.MeganMapDB, false, new ProgressPercentage());
                if (class2AccessionFile.get(cName).length() > 0)
                    idMappers[i].loadMappingFile(class2AccessionFile.get(cName), IdMapper.MapType.Accession, false, new ProgressPercentage());
                if (class2SynonymsFile.get(cName).length() > 0)
                    idMappers[i].loadMappingFile(class2SynonymsFile.get(cName), IdMapper.MapType.Synonyms, false, new ProgressPercentage());
            }
        }

        /*
         * process each set of files:
         */
        for (int i = 0; i < blastFiles.length; i++) {
            final int iOutput;
            if (processInPairs) {
                if ((i % 2) == 1)
                    continue; // skip odd numbers
                iOutput = i / 2;
                System.err.println("Processing " + blastFormat + " files: " + blastFiles[i] + ", " + blastFiles[i + 1]);
                System.err.println("Output file:  " + outputFiles[iOutput]);
            } else {
                iOutput = i;
                System.err.println("Processing " + blastFormat + " file: " + blastFiles[i]);
                System.err.println("Output file:  " + outputFiles[i]);
            }

            ProgressListener progressListener = new ProgressPercentage();

            final Document doc = new Document();
            doc.getActiveViewers().add(Classification.Taxonomy);
            doc.getActiveViewers().addAll(cNames);
            doc.setMinScore(minScore);
            doc.setMinPercentIdentity(minPercentIdentity);
            doc.setMaxExpected(maxExpected);
            doc.setTopPercent(topPercent);
            doc.setMinSupportPercent(minSupportPercent);
            doc.setMinSupport(minSupport);
            doc.setPairedReads(pairedReads);
            doc.setPairedReadSuffixLength(pairedReadsSuffixLength);
            if (blastMode == BlastMode.Unknown)
                doc.setBlastMode(BlastModeUtils.getBlastMode(blastFiles[0]));
            else
                doc.setBlastMode(blastMode);
            doc.setLcaAlgorithm(lcaAlgorithm);
            doc.setLcaCoveragePercent(lcaCoveragePercent);
            doc.setMinPercentReadToCover(minPercentReadToCover);
            doc.setMinPercentReferenceToCover(minPercentReferenceToCover);
            doc.setLongReads(longReads);
            doc.setReadAssignmentMode(readAssignmentMode);

            if (contaminantsFile.length() > 0) {
                ContaminantManager contaminantManager = new ContaminantManager();
                contaminantManager.read(contaminantsFile);
                System.err.println(String.format("Contaminants profile: %,d input, %,d total", contaminantManager.inputSize(), contaminantManager.size()));
                doc.getDataTable().setContaminants(contaminantManager.getTaxonIdsString());
                doc.setUseContaminantFilter(contaminantManager.size() > 0);
            }

            if (!processInPairs)
                createRMA6FileFromBLAST("BLAST2RMA6", blastFiles[i], blastFormat, readsFiles[i], outputFiles[iOutput], useCompression, doc, maxMatchesPerRead, progressListener);
            else
                createRMA6FileFromBLASTPair("BLAST2RMA6", blastFiles[i], blastFiles[i + 1], blastFormat, readsFiles[i], readsFiles[i + 1], outputFiles[iOutput], useCompression, doc, maxMatchesPerRead, progressListener);

            progressListener.close();

            final RMA6Connector connector = new RMA6Connector(outputFiles[i]);

            if (metaDataFiles.length > 0) {
                try {
                    System.err.println("Saving metadata:");
                    SampleAttributeTable sampleAttributeTable = new SampleAttributeTable();
                    sampleAttributeTable.read(new FileReader(metaDataFiles[Math.min(iOutput, metaDataFiles.length - 1)]),
                            Collections.singletonList(Basic.getFileBaseName(Basic.getFileNameWithoutPath(outputFiles[iOutput]))), false);
                    Map<String, byte[]> label2data = new HashMap<>();
                    label2data.put(SampleAttributeTable.SAMPLE_ATTRIBUTES, sampleAttributeTable.getBytes());
                    connector.putAuxiliaryData(label2data);
                    System.err.println("done");
                } catch (Exception ex) {
                    Basic.caught(ex);
                }
            }
            progressListener.incrementProgress();
        }
    }

    /**
     * create an RMA6 file from a BLAST file
     *
     * @param creator
     * @param blastFile
     * @param format
     * @param queryFile
     * @param rma6FileName
     * @param useCompression
     * @param doc
     * @param maxMatchesPerRead
     * @param progressListener
     * @throws IOException
     * @throws CanceledException
     */
    private static void createRMA6FileFromBLAST(String creator, String blastFile, BlastFileFormat format, String queryFile, String rma6FileName, boolean useCompression, Document doc,
                                                int maxMatchesPerRead, ProgressListener progressListener) throws IOException, CanceledException, SQLException {
        final RMA6FromBlastCreator rma6Creator = new RMA6FromBlastCreator(creator, format, doc.getBlastMode(), new String[]{blastFile}, new String[]{queryFile}, rma6FileName, useCompression, doc, maxMatchesPerRead);
        rma6Creator.parseFiles(progressListener);
    }

    /**
     * create an RMA6 file from a pair of BLAST files
     *
     * @param creator
     * @param blastFile1
     * @param blastFile2
     * @param format
     * @param queryFile1
     * @param queryFile2
     * @param rma6FileName
     * @param useCompression
     * @param doc
     * @param maxMatchesPerRead
     * @param progressListener
     * @throws IOException
     * @throws CanceledException
     */
    private static void createRMA6FileFromBLASTPair(String creator, String blastFile1, String blastFile2, BlastFileFormat format, String queryFile1, String queryFile2, String rma6FileName, boolean useCompression, Document doc,
                                                    int maxMatchesPerRead, ProgressListener progressListener) throws IOException, CanceledException, SQLException {
        final RMA6FromBlastCreator rma6Creator = new RMA6FromBlastCreator(creator, format, doc.getBlastMode(), new String[]{blastFile1, blastFile2}, new String[]{queryFile1, queryFile2}, rma6FileName, useCompression, doc, maxMatchesPerRead);
        rma6Creator.parseFiles(progressListener);
    }

}
