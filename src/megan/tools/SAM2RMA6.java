/*
 * SAM2RMA6.java Copyright (C) 2020. Daniel H. Huson
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

import jloda.fx.util.ProgramExecutorService;
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
import megan.util.SAMFileFilter;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * compute an RMA6 file from a SAM file generated by DIAMOND or MALT
 * Daniel Huson, 3.2012
 */
public class SAM2RMA6 {
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
            ProgramProperties.setProgramName("SAM2RMA6");
            ProgramProperties.setProgramVersion(megan.main.Version.SHORT_DESCRIPTION);

            PeakMemoryUsageMonitor.start();
            (new SAM2RMA6()).run(args);
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
    private void run(String[] args) throws UsageException, IOException, SQLException {
        CommandManager.getGlobalCommands().addAll(ClassificationCommandHelper.getGlobalCommands());

        final ArgsOptions options = new ArgsOptions(args, this, "Computes a MEGAN RMA (.rma) file from a SAM (.sam) file that was created by DIAMOND or MALT");
        options.setVersion(ProgramProperties.getProgramVersion());
        options.setLicense("Copyright (C) 2020 Daniel H. Huson. This program comes with ABSOLUTELY NO WARRANTY.");
        options.setAuthors("Daniel H. Huson");

        options.comment("Input");
        final String[] samFiles = options.getOptionMandatory("-i", "in", "Input SAM file[s] generated by DIAMOND or MALT (gzipped ok)", new String[0]);
        String[] readsFiles = options.getOption("-r", "reads", "Reads file(s) (fasta or fastq, gzipped ok)", new String[0]);
        final String[] metaDataFiles = options.getOption("-mdf", "metaDataFile", "Files containing metadata to be included in RMA6 files", new String[0]);

        options.comment("Output");
        String[] outputFiles = options.getOptionMandatory("-o", "out", "Output file(s), one for each input file, or a directory", new String[0]);
        boolean useCompression = options.getOption("-c", "useCompression", "Compress reads and matches in RMA file (smaller files, longer to generate", true);

        options.comment("Reads");
        final boolean pairedReads = options.getOption("-p", "paired", "Reads are paired", false);
        final int pairedReadsSuffixLength = options.getOption("-ps", "pairedSuffixLength", "Length of name suffix used to distinguish between name of read and its mate", 0);
        options.comment("Parameters");
        boolean longReads = options.getOption("-lg", "longReads", "Parse and analyse as long reads", Document.DEFAULT_LONG_READS);

        final int maxMatchesPerRead = options.getOption("-m", "maxMatchesPerRead", "Max matches per read", 100);
        final boolean runClassifications = options.getOption("-class", "classify", "Run classification algorithm", true);
        final float minScore = options.getOption("-ms", "minScore", "Min score", Document.DEFAULT_MINSCORE);
        final float maxExpected = options.getOption("-me", "maxExpected", "Max expected", Document.DEFAULT_MAXEXPECTED);
        final float topPercent = options.getOption("-top", "topPercent", "Top percent", Document.DEFAULT_TOPPERCENT);
        final float minSupportPercent = options.getOption("-supp", "minSupportPercent", "Min support as percent of assigned reads (0==off)", Document.DEFAULT_MINSUPPORT_PERCENT);
        final int minSupport = options.getOption("-sup", "minSupport", "Min support", Document.DEFAULT_MINSUPPORT);

        final float minPercentReadToCover = options.getOption("-mrc", "minPercentReadCover", "Min percent of read length to be covered by alignments", Document.DEFAULT_MIN_PERCENT_READ_TO_COVER);
        final float minPercentReferenceToCover = options.getOption("-mrefc", "minPercentReferenceCover", "Min percent of reference length to be covered by alignments", Document.DEFAULT_MIN_PERCENT_REFERENCE_TO_COVER);

        final Document.LCAAlgorithm lcaAlgorithm = Document.LCAAlgorithm.valueOfIgnoreCase(options.getOption("-alg", "lcaAlgorithm", "Set the LCA algorithm to use for taxonomic assignment",
                Document.LCAAlgorithm.values(), longReads ? Document.DEFAULT_LCA_ALGORITHM_LONG_READS.toString() : Document.DEFAULT_LCA_ALGORITHM_SHORT_READS.toString()));
        final float lcaCoveragePercent = options.getOption("-lcp", "lcaCoveragePercent", "Set the percent for the LCA to cover", Document.DEFAULT_LCA_COVERAGE_PERCENT_SHORT_READS);

        final Document.ReadAssignmentMode readAssignmentMode = Document.ReadAssignmentMode.valueOfIgnoreCase(options.getOption("-ram", "readAssignmentMode", "Set the read assignment mode",
                Document.ReadAssignmentMode.values(), longReads ? Document.DEFAULT_READ_ASSIGNMENT_MODE_LONG_READS.toString() : Document.DEFAULT_READ_ASSIGNMENT_MODE_SHORT_READS.toString()));

        final String contaminantsFile = options.getOption("-cf", "conFile", "File of contaminant taxa (one Id or name per line)", "");

        options.comment("Classification support:");

        final String mapDBFile = options.getOption("-mdb", "mapDB", "MEGAN mapping db (file megan-map.db)", "");
        final Set<String> selectedClassifications = new HashSet<>(Arrays.asList(options.getOption("-on", "only", "Use only named classifications (if not set: use all)", new String[0])));

        options.comment("Deprecated classification support:");

        final boolean parseTaxonNames = options.getOption("-tn", "parseTaxonNames", "Parse taxon names", true);
        final String acc2TaxaFile = options.getOption("-a2t", "acc2taxa", "Accession-to-Taxonomy mapping file", "");
        final String synonyms2TaxaFile = options.getOption("-s2t", "syn2taxa", "Synonyms-to-Taxonomy mapping file", "");
        {
            final String tags = options.getOption("-t4t" , "tags4taxonomy" , "Tags for taxonomy id parsing (must set to activate id parsing)", "").trim();
            ProgramProperties.preset("TaxonomyTags", tags);
            ProgramProperties.preset("TaxonomyParseIds", tags.length() > 0);
        }

        final HashMap<String, String> class2AccessionFile = new HashMap<>();
        final HashMap<String, String> class2SynonymsFile = new HashMap<>();

        for (String cName : ClassificationManager.getAllSupportedClassificationsExcludingNCBITaxonomy()) {
            class2AccessionFile.put(cName, options.getOption("-a2" + cName.toLowerCase(), "acc2" + cName.toLowerCase(), "Accession-to-" + cName + " mapping file", ""));
            class2SynonymsFile.put(cName, options.getOption("-s2" + cName.toLowerCase(), "syn2" + cName.toLowerCase(), "Synonyms-to-" + cName + " mapping file", ""));
            final String tags = options.getOption("-t4" + cName.toLowerCase(), "tags4" + cName.toLowerCase(), "Tags for " + cName + " id parsing (must set to activate id parsing)", "").trim();
            ProgramProperties.preset(cName + "Tags", tags);
            ProgramProperties.preset(cName + "ParseIds", tags.length() > 0);
        }

        ProgramProperties.preset(IdParser.PROPERTIES_FIRST_WORD_IS_ACCESSION, options.getOption("-fwa", "firstWordIsAccession", "First word in reference header is accession number (set to 'true' for NCBI-nr downloaded Sep 2016 or later)", true));
        ProgramProperties.preset(IdParser.PROPERTIES_ACCESSION_TAGS, options.getOption("-atags", "accessionTags", "List of accession tags", ProgramProperties.get(IdParser.PROPERTIES_ACCESSION_TAGS, IdParser.ACCESSION_TAGS)));

        options.comment(ArgsOptions.OTHER);
        ProgramExecutorService.setNumberOfCoresToUse(options.getOption("-t", "threads", "Number of threads", 8));

        options.done();

        final String propertiesFile;
        if (ProgramProperties.isMacOS())
            propertiesFile = System.getProperty("user.home") + "/Library/Preferences/Megan.def";
        else
            propertiesFile = System.getProperty("user.home") + File.separator + ".Megan.def";
        MeganProperties.initializeProperties(propertiesFile);

        if (minSupport > 0 && minSupportPercent > 0)
            throw new IOException("Please specify a positive value for either --minSupport or --minSupportPercent, but not for both");

        for (String fileName : samFiles) {
            Basic.checkFileReadableNonEmpty(fileName);
            if (!SAMFileFilter.getInstance().accept(fileName))
                throw new IOException("File not in SAM format (or incorrect file suffix?): " + fileName);
        }

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
            if ((selectedClassifications.size() == 0 || selectedClassifications.contains(cName))
                    && (mapDBClassifications.contains(cName) || class2AccessionFile.get(cName).length() > 0 || class2SynonymsFile.get(cName).length() > 0))
                cNames.add(cName);
        }
        if (cNames.size() > 0)
            System.err.println("Functional classifications to use: " + Basic.toString(cNames, ", "));

        if (outputFiles.length == 1) {
            if (samFiles.length == 1) {
                if ((new File(outputFiles[0]).isDirectory()))
                    outputFiles[0] = (new File(outputFiles[0], Basic.replaceFileSuffix(Basic.getFileNameWithoutPath(Basic.getFileNameWithoutZipOrGZipSuffix(samFiles[0])), ".rma6"))).getPath();
            } else if (samFiles.length > 1) {
                if (!(new File(outputFiles[0]).isDirectory()))
                    throw new IOException("Multiple files given, but given single output is not a directory");
                String outputDirectory = (new File(outputFiles[0])).getParent();
                outputFiles = new String[samFiles.length];

                for (int i = 0; i < samFiles.length; i++)
                    outputFiles[i] = new File(outputDirectory, Basic.replaceFileSuffix(Basic.getFileNameWithoutZipOrGZipSuffix(Basic.getFileNameWithoutPath(samFiles[i])), ".rma6")).getPath();
            }
        } else // output.length >1
        {
            if (samFiles.length != outputFiles.length)
                throw new IOException("Number of input and output files do not match");
        }

        if (metaDataFiles.length > 1 && metaDataFiles.length != samFiles.length) {
            throw new IOException("Number of metadata files (" + metaDataFiles.length + ") doesn't match number of SAM files (" + samFiles.length + ")");
        }

        if (readsFiles.length == 0) {
            readsFiles = new String[samFiles.length];
            Arrays.fill(readsFiles, "");
        } else if (readsFiles.length != samFiles.length)
            throw new IOException("Number of reads files must equal number of SAM files");

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
        for (int i = 0; i < samFiles.length; i++) {
            System.err.println("Current SAM file: " + samFiles[i]);
            if (i < readsFiles.length)
                System.err.println("Reads file:   " + readsFiles[i]);
            System.err.println("Output file:  " + outputFiles[i]);

            ProgressListener progressListener = new ProgressPercentage();

            final Document doc = new Document();
            doc.getActiveViewers().add(Classification.Taxonomy);
            doc.getActiveViewers().addAll(cNames);
            doc.setLongReads(longReads);
            doc.setMinScore(minScore);
            doc.setMaxExpected(maxExpected);
            doc.setTopPercent(topPercent);
            doc.setMinSupportPercent(minSupportPercent);
            doc.setMinSupport(minSupport);
            doc.setPairedReads(pairedReads);
            doc.setPairedReadSuffixLength(pairedReadsSuffixLength);
            doc.setBlastMode(BlastModeUtils.determineBlastModeSAMFile(samFiles[i]));
            doc.setLcaAlgorithm(lcaAlgorithm);
            doc.setLcaCoveragePercent(lcaCoveragePercent);
            doc.setMinPercentReadToCover(minPercentReadToCover);
            doc.setMinPercentReferenceToCover(minPercentReferenceToCover);
            doc.setReadAssignmentMode(readAssignmentMode);

            if (contaminantsFile.length() > 0) {
                ContaminantManager contaminantManager = new ContaminantManager();
                contaminantManager.read(contaminantsFile);
                System.err.println(String.format("Contaminants profile: %,d input, %,d total", contaminantManager.inputSize(), contaminantManager.size()));
                doc.getDataTable().setContaminants(contaminantManager.getTaxonIdsString());
                doc.setUseContaminantFilter(contaminantManager.size() > 0);
            }

            createRMA6FileFromSAM("SAM2RMA6", samFiles[i], readsFiles[i], outputFiles[i], useCompression, doc, maxMatchesPerRead, progressListener);

            progressListener.close();

            final RMA6Connector connector = new RMA6Connector(outputFiles[i]);

            if (metaDataFiles.length > 0) {
                try {
                    System.err.println("Saving metadata:");
                    SampleAttributeTable sampleAttributeTable = new SampleAttributeTable();
                    sampleAttributeTable.read(new FileReader(metaDataFiles[Math.min(i, metaDataFiles.length - 1)]),
                            Collections.singletonList(Basic.getFileBaseName(Basic.getFileNameWithoutPath(outputFiles[i]))), false);
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
     * create an RMA6 file from a SAM file (generated by DIAMOND or MALT)
     *
     * @param samFile
     * @param rma6FileName
     * @param maxMatchesPerRead
     * @param progressListener  @throws CanceledException
     */
    private static void createRMA6FileFromSAM(String creator, String samFile, String queryFile, String rma6FileName, boolean useCompression, Document doc,
                                              int maxMatchesPerRead, ProgressListener progressListener) throws IOException, SQLException {
        final RMA6FromBlastCreator rma6Creator =
                new RMA6FromBlastCreator(creator, BlastFileFormat.SAM, doc.getBlastMode(), new String[]{samFile}, new String[]{queryFile}, rma6FileName, useCompression, doc, maxMatchesPerRead);
        rma6Creator.parseFiles(progressListener);
    }
}
