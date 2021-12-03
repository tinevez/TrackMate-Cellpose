package fiji.plugin.trackmate.cellpose;

import static fiji.plugin.trackmate.detection.DetectorKeys.DEFAULT_TARGET_CHANNEL;
import static fiji.plugin.trackmate.detection.DetectorKeys.KEY_TARGET_CHANNEL;
import static fiji.plugin.trackmate.detection.ThresholdDetectorFactory.KEY_SIMPLIFY_CONTOURS;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.detection.LabeImageDetectorFactory;
import fiji.plugin.trackmate.detection.SpotGlobalDetector;
import fiji.plugin.trackmate.util.TMUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.Concatenator;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.Interval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.display.imagej.ImgPlusViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class CellposeDetector< T extends RealType< T > & NativeType< T > > implements SpotGlobalDetector< T >
{
	private final static String BASE_ERROR_MESSAGE = "CellposeDetector: ";

	private final static File CELLPOSE_LOG_FILE = new File( new File( System.getProperty( "user.home" ), ".cellpose" ), "run.log" );

	protected final ImgPlus< T > img;

	protected final Interval interval;

	private final CellposeSettings cellposeSettings;

	private final Logger logger;

	protected String baseErrorMessage;

	protected String errorMessage;

	protected long processingTime;

	protected SpotCollection spots;

	public CellposeDetector(
			final ImgPlus< T > img,
			final Interval interval,
			final CellposeSettings cellposeSettings,
			final Logger logger )
	{
		this.img = img;
		this.interval = interval;
		this.cellposeSettings = cellposeSettings;
		this.logger = ( logger == null ) ? Logger.VOID_LOGGER : logger;
		this.baseErrorMessage = BASE_ERROR_MESSAGE;
	}

	@Override
	public boolean process()
	{
		final long start = System.currentTimeMillis();

		/*
		 * Prepare tmp dir.
		 */
		Path tmpDir = null;
		try
		{
			tmpDir = Files.createTempDirectory( "TrackMate-Cellpose_" );
			recursiveDeleteOnShutdownHook( tmpDir );
		}
		catch ( final IOException e1 )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Could not create tmp dir to save and load images:\n" + e1.getMessage();
			return false;
		}

		/*
		 * Do we have time? If yes we need to fetch the min time index to
		 * reposition the spots in the correct frame at the end of the
		 * detection.
		 */
		final int timeIndex = img.dimensionIndex( Axes.TIME );
		final int minT = ( int ) ( ( timeIndex < 0 ) ? 0 : interval.min( interval.numDimensions() - 1 ) );
		final double frameInterval = ( timeIndex < 0 ) ? 1. : img.averageScale( timeIndex );

		/*
		 * Save time-points as individual frames.
		 */

		logger.log( "Saving single time-points.\n" );
		final Function< Long, String > nameGen = ( frame ) -> String.format( "%d", frame );
		final List< ImagePlus > imps = crop( img, interval, nameGen );
		// Careful, now time starts at 0, even if in the interval it is not the
		// case.
		for ( int t = 0; t < imps.size(); t++ )
		{
			final ImagePlus imp = imps.get( t );
			final String name = nameGen.apply( ( long ) t ) + ".tif";
			IJ.saveAsTiff( imp, Paths.get( tmpDir.toString(), name ).toString() );
		}

		/*
		 * Run Cellpose.
		 */

		// Redirect log to logger.
		try
		{
			final List< String > cmd = cellposeSettings.toCmdLine( tmpDir.toString() );
			logger.setStatus( "Running Cellpose" );
			logger.log( "Running Cellpose with args:\n" );
			logger.log( String.join( " ", cmd ) );
			logger.log( "\n" );
			final ProcessBuilder pb = new ProcessBuilder( cmd );
			pb.redirectOutput( ProcessBuilder.Redirect.INHERIT );
			pb.redirectError( ProcessBuilder.Redirect.INHERIT );

			final Process p = pb.start();
			final Tailer tailer = Tailer.create( CELLPOSE_LOG_FILE, new LoggerTailerListener( logger ), 200, true );
			final Thread thread = new Thread( tailer );
			thread.setDaemon( true );
			thread.start();
			p.waitFor();
			tailer.stop();
		}
		catch ( final Exception e )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Problem running Cellpose:\n" + e.getMessage();
			e.printStackTrace();
			return false;
		}
		finally
		{
			logger.setStatus( "" );
		}

		/*
		 * Get the result masks back.
		 */

		logger.log( "Reading Cellpose masks.\n" );
		final List< ImagePlus > masks = new ArrayList<>( imps.size() );
		for ( int t = 0; t < imps.size(); t++ )
		{
			final String name = nameGen.apply( ( long ) t ) + "_cp_masks.png";
			final String path = new File( tmpDir.toString(), name ).getAbsolutePath();
			final ImagePlus tpImp = IJ.openImage( path );
			if ( null == tpImp )
			{
				errorMessage = BASE_ERROR_MESSAGE + "Could not find results file for timepoint: " + name;
				return false;
			}
			masks.add( tpImp );
		}
		final Concatenator concatenator = new Concatenator();
		final ImagePlus output = concatenator.concatenateHyperstacks(
				masks.toArray( new ImagePlus[] {} ),
				img.getName() + "_CellposeOutput", false );

		// Copy calibration.
		final double[] calibration = TMUtils.getSpatialCalibration( img );
		output.getCalibration().pixelWidth = calibration[ 0 ];
		output.getCalibration().pixelHeight = calibration[ 1 ];
		output.getCalibration().pixelDepth = calibration[ 2 ];
		output.setDimensions( 1, imps.get( 0 ).getNSlices(), imps.size() );
		output.setOpenAsHyperStack( true );

		/*
		 * Run in the label detector.
		 */

		logger.log( "Converting masks to spots.\n" );
		final Settings labelImgSettings = new Settings( output );
		final LabeImageDetectorFactory< ? > labeImageDetectorFactory = new LabeImageDetectorFactory<>();
		final Map< String, Object > detectorSettings = labeImageDetectorFactory.getDefaultSettings();
		detectorSettings.put( KEY_TARGET_CHANNEL, DEFAULT_TARGET_CHANNEL );
		detectorSettings.put( KEY_SIMPLIFY_CONTOURS, cellposeSettings.simplifyContours );
		labelImgSettings.detectorFactory = labeImageDetectorFactory;
		labelImgSettings.detectorSettings = detectorSettings;

		final TrackMate labelImgTrackMate = new TrackMate( labelImgSettings );
		if ( !labelImgTrackMate.execDetection() )
		{
			errorMessage = BASE_ERROR_MESSAGE + labelImgTrackMate.getErrorMessage();
			return false;
		}
		final SpotCollection tmpSpots = labelImgTrackMate.getModel().getSpots();

		/*
		 * Reposition spots with respect to the interval and time.
		 */
		final List< Spot > slist = new ArrayList<>();
		for ( final Spot spot : tmpSpots.iterable( false ) )
		{
			for ( int d = 0; d < interval.numDimensions() - 1; d++ )
			{
				final double pos = spot.getDoublePosition( d ) + interval.min( d ) * calibration[ d ];
				spot.putFeature( Spot.POSITION_FEATURES[ d ], Double.valueOf( pos ) );
			}
			// Shift in time.
			final int frame = spot.getFeature( Spot.FRAME ).intValue() + minT;
			spot.putFeature( Spot.POSITION_T, frame * frameInterval );
			spot.putFeature( Spot.FRAME, Double.valueOf( frame ) );
			slist.add( spot );
		}
		spots = SpotCollection.fromCollection( slist );

		/*
		 * End.
		 */

		final long end = System.currentTimeMillis();
		this.processingTime = end - start;

		return true;
	}

	private static final < T extends RealType< T > & NativeType< T > > List< ImagePlus > crop( final ImgPlus< T > img, final Interval interval, final Function< Long, String > nameGen )
	{
		final int zIndex = img.dimensionIndex( Axes.Z );
		final int cIndex = img.dimensionIndex( Axes.CHANNEL );
		final Interval cropInterval;
		if ( zIndex < 0 )
		{
			// 2D
			if ( cIndex < 0 )
				cropInterval = Intervals.createMinMax(
						interval.min( 0 ), interval.min( 1 ),
						interval.max( 0 ), interval.max( 1 ) );
			else
				// Include all channels
				cropInterval = Intervals.createMinMax(
						interval.min( 0 ), interval.min( 1 ), img.min( cIndex ),
						interval.max( 0 ), interval.max( 1 ), img.max( cIndex ) );
		}
		else
		{
			if ( cIndex < 0 )
				cropInterval = Intervals.createMinMax(
						interval.min( 0 ), interval.min( 1 ), interval.min( 2 ),
						interval.max( 0 ), interval.max( 1 ), interval.max( 2 ) );
			else
				cropInterval = Intervals.createMinMax(
						interval.min( 0 ), interval.min( 1 ), interval.min( 2 ), img.min( cIndex ),
						interval.max( 0 ), interval.max( 1 ), interval.max( 2 ), img.max( cIndex ) );
		}

		final List< ImagePlus > imps = new ArrayList<>();
		final int timeIndex = img.dimensionIndex( Axes.TIME );
		if ( timeIndex < 0 )
		{
			// No time.
			final IntervalView< T > crop = Views.interval( img, cropInterval );
			final String name = nameGen.apply( 0l ) + ".tif";
			imps.add( ImageJFunctions.wrap( crop, name ) );
		}
		else
		{
			// In the interval, time is always the last.
			final long minT = interval.min( interval.numDimensions() - 1 );
			final long maxT = interval.max( interval.numDimensions() - 1 );
			for ( long t = minT; t <= maxT; t++ )
			{
				final ImgPlus< T > tp = ImgPlusViews.hyperSlice( img, timeIndex, t );
				// possibly 2D or 3D with or without channel.
				final IntervalView< T > crop = Views.interval( tp, cropInterval );
				final String name = nameGen.apply( t ) + ".tif";
				imps.add( ImageJFunctions.wrap( crop, name ) );
			}
		}
		return imps;
	}

	@Override
	public SpotCollection getResult()
	{
		return spots;
	}

	@Override
	public boolean checkInput()
	{
		if ( null == img )
		{
			errorMessage = baseErrorMessage + "Image is null.";
			return false;
		}
		if ( img.dimensionIndex( Axes.Z ) >= 0 )
		{
			errorMessage = baseErrorMessage + "Image must be 2D over time, got an image with multiple Z.";
			return false;
		}
		return true;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}

	/**
	 * Add a hook to delete the content of given path when Fiji quits. Taken
	 * from https://stackoverflow.com/a/20280989/201698
	 * 
	 * @param path
	 */
	private static void recursiveDeleteOnShutdownHook( final Path path )
	{
		Runtime.getRuntime().addShutdownHook( new Thread( new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					Files.walkFileTree( path, new SimpleFileVisitor< Path >()
					{
						@Override
						public FileVisitResult visitFile( final Path file, final BasicFileAttributes attrs ) throws IOException
						{
							Files.delete( file );
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult postVisitDirectory( final Path dir, final IOException e ) throws IOException
						{
							if ( e == null )
							{
								Files.delete( dir );
								return FileVisitResult.CONTINUE;
							}
							throw e;
						}
					} );
				}
				catch ( final IOException e )
				{
					throw new RuntimeException( "Failed to delete " + path, e );
				}
			}
		} ) );
	}

	private static class LoggerTailerListener extends TailerListenerAdapter
	{
		private final Logger logger;

		private final static Pattern PERCENTAGE_PATTERN = Pattern.compile( "\\b(?<!\\.)(?!0+(?:\\.0+)?%)(?:\\d|[1-9]\\d|100)(?:(?<!100)\\.\\d+)?%" );

		public LoggerTailerListener( final Logger logger )
		{
			this.logger = logger;
		}

		@Override
		public void handle( final String line )
		{
			logger.log( line + '\n' );
			// Do we have percentage?
			final Matcher matcher = PERCENTAGE_PATTERN.matcher( line );
			if ( matcher.matches() )
			{
				final String percent = matcher.group( 1 );
				logger.setProgress( Double.valueOf( percent ) / 100. );
			}
		}
	}
}
