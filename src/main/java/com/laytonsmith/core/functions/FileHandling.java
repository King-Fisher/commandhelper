package com.laytonsmith.core.functions;

import com.laytonsmith.PureUtilities.FileUtility;
import com.laytonsmith.PureUtilities.SSHWrapper;
import com.laytonsmith.PureUtilities.ZipReader;
import com.laytonsmith.abstraction.StaticLayer;
import com.laytonsmith.annotations.api;
import com.laytonsmith.annotations.noboilerplate;
import com.laytonsmith.core.CHVersion;
import com.laytonsmith.core.LogLevel;
import com.laytonsmith.core.ObjectGenerator;
import com.laytonsmith.core.Security;
import com.laytonsmith.core.Static;
import com.laytonsmith.core.Threader;
import com.laytonsmith.core.arguments.Argument;
import com.laytonsmith.core.arguments.ArgumentBuilder;
import com.laytonsmith.core.arguments.Generic;
import com.laytonsmith.core.constructs.CArray;
import com.laytonsmith.core.constructs.CClosure;
import com.laytonsmith.core.constructs.CInt;
import com.laytonsmith.core.constructs.CString;
import com.laytonsmith.core.constructs.CVoid;
import com.laytonsmith.core.constructs.Construct;
import com.laytonsmith.core.constructs.Target;
import com.laytonsmith.core.environments.Environment;
import com.laytonsmith.core.exceptions.CancelCommandException;
import com.laytonsmith.core.exceptions.ConfigRuntimeException;
import com.laytonsmith.core.functions.Exceptions.ExceptionType;
import com.laytonsmith.core.natives.interfaces.Mixed;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 *
 * @author lsmith
 */
public class FileHandling {

	public static String docs(){
		return "This class contains methods that help manage files on the file system. Most are restricted with the base-dir setting"
			+ " in your preferences.";
	}
	
	@api
	@noboilerplate
	public static class read extends AbstractFunction {

		public static String file_get_contents(String file_location) throws Exception {
			return new ZipReader(new File(file_location)).getFileContents();
		}

		public String getName() {
			return "read";
		}

		public Integer[] numArgs() {
			return new Integer[]{1};
		}

		public Construct exec(Target t, Environment env, Mixed... args) throws CancelCommandException, ConfigRuntimeException {
			String location = args[0].val();
			location = new File(t.file().getParentFile(), location).getAbsolutePath();
			//Verify this file is not above the craftbukkit directory (or whatever directory the user specified
			if (!Security.CheckSecurity(location)) {
				throw new ConfigRuntimeException("You do not have permission to access the file '" + location + "'",
					Exceptions.ExceptionType.SecurityException, t);
			}
			try {
				String s = file_get_contents(location);
				s = s.replaceAll("\n|\r\n", "\n");
				return new CString(s, t);
			} catch (Exception ex) {
				Static.getLogger().log(Level.SEVERE, "Could not read in file while attempting to find " + new File(location).getAbsolutePath()
					+ "\nFile " + (new File(location).exists() ? "exists" : "does not exist"));
				throw new ConfigRuntimeException("File could not be read in.",
					Exceptions.ExceptionType.IOException, t);
			}
		}

		public String docs() {
			return "Reads in a file from the file system at location var1 and returns it as a string. The path is relative to"
				+ " the file that is being run, not CommandHelper. If the file is not found, or otherwise can't be read in, an IOException is thrown."
				+ " If the file specified is not within base-dir (as specified in the preferences file), a SecurityException is thrown."
				+ " The line endings for the string returned will always be \\n, even if they originally were \\r\\n.";
		}
		
		public Argument returnType() {
			return new Argument("", CString.class);
		}

		public ArgumentBuilder arguments() {
			return ArgumentBuilder.Build(
						new Argument("The path to the file to read in, relative to the file this is being run from."
					+ " It is assumed that the contents of the file are text based.", CString.class, "filePath")
					);
		}

		public Exceptions.ExceptionType[] thrown() {
			return new Exceptions.ExceptionType[]{Exceptions.ExceptionType.IOException, Exceptions.ExceptionType.SecurityException};
		}

		public boolean isRestricted() {
			return true;
		}

		public CHVersion since() {
			return CHVersion.V3_0_1;
		}

		public Boolean runAsync() {
			//Because we do disk IO
			return true;
		}
		
		@Override
		public LogLevel profileAt() {
			return LogLevel.DEBUG;
		}
	}
	
	@api
	@noboilerplate
	public static class async_read extends AbstractFunction{

		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.SecurityException};
		}

		public boolean isRestricted() {
			return true;
		}

		public Boolean runAsync() {
			return null;
		}

		public Construct exec(final Target t, Environment environment, Mixed... args) throws ConfigRuntimeException {
			final String file = args[0].val();
			final CClosure callback;
			if(!(args[1] instanceof CClosure)){
				throw new ConfigRuntimeException("Expected paramter 2 of " + getName() + " to be a closure!", t);
			} else {
				callback = ((CClosure)args[1]);
			}
			if(!Security.CheckSecurity(file)){
				throw new ConfigRuntimeException("You do not have permission to access the file '" + file + "'", ExceptionType.SecurityException, t);
			}
			Threader.GetThreader().submit(new Runnable() {

				public void run() {
					String returnString = null;					
					ConfigRuntimeException exception = null;
					if(file.contains("@")){
						try {
							//It's an SCP transfer
							returnString = SSHWrapper.SCPReadString(file);
						} catch (IOException ex) {
							exception = new ConfigRuntimeException(ex.getMessage(), ExceptionType.IOException, t, ex);
						}
					} else {
						try {
							//It's a local file read
							returnString = FileUtility.read(new File(t.file().getParentFile(), file));
						} catch (IOException ex) {
							exception = new ConfigRuntimeException(ex.getMessage(), ExceptionType.IOException, t, ex);
						}
					}
					final CString cret;
					if(returnString == null){
						cret = Construct.GetNullConstruct(CString.class, t);
					} else {
						cret = new CString(returnString, t);
					}
					final CArray cex;
					if(exception == null){
						cex = Construct.GetNullConstruct(CArray.class, t);
					} else {
						cex = ObjectGenerator.GetGenerator().exception(exception, t);
					}
					StaticLayer.SetFutureRunnable(0, new Runnable() {

						public void run() {
							callback.execute(new Construct[]{cret, cex});
						}
					});
				}
			});
			return new CVoid(t);
		}

		public String getName() {
			return "async_read";
		}

		public Integer[] numArgs() {
			return new Integer[]{2};
		}

		public String docs() {
			return "Asyncronously reads in a file. ---- "
				+ " This may be a remote file accessed with an SCP style path. (See the [[CommandHelper/SCP|wiki article]]"
				+ " about SCP credentials for more information.) If the file is not found, or otherwise can't be read in, an IOException is thrown."
				+ " If the file specified is not within base-dir (as specified in the preferences file), a SecurityException is thrown."
				+ " (This is not applicable for remote files)"
				+ " The line endings for the string returned will always be \\n, even if they originally were \\r\\n."
				+ " This method will immediately return, and asynchronously read in the file, and finally send the contents"
				+ " to the callback once the task completes. The callback should have the following signature: closure(@contents, @exception, &lt;code&gt;)."
				+ " If @contents is null, that indicates that an exception occured, and @exception will not be null, but instead have an"
				+ " exeption array. Otherwise, @contents will contain the file's contents, and @exception will be null. This method is useful"
				+ " to use in two cases, either you need a remote file via SCP, or a local file is big enough that you notice a delay when"
				+ " simply using the read() function.";
		}
		
		public Argument returnType() {
			return Argument.VOID;
		}

		public ArgumentBuilder arguments() {
			return ArgumentBuilder.Build(
						new Argument("The path to the file to read in, relative to the file this is being run from."
					+ " It is assumed that the contents of the file are text based.", CString.class, "fileOrSCPPath"),
						new Argument("The callback, which will receieve the text read in (or the exception).", CClosure.class, "callback").setGenerics(new Generic(CString.class), new Generic(CArray.class))
					);
		}

		public CHVersion since() {
			return CHVersion.V3_3_1;
		}
		
	}
	
	@api
	public static class file_size extends AbstractFunction {

		public ExceptionType[] thrown() {
			return new ExceptionType[]{ExceptionType.IOException, ExceptionType.SecurityException};
		}

		public boolean isRestricted() {
			return true;
		}

		public Boolean runAsync() {
			return null;
		}

		public Construct exec(Target t, Environment environment, Mixed... args) throws ConfigRuntimeException {
			String location = args[0].val();
			if(!Security.CheckSecurity(location)){
				throw new ConfigRuntimeException("You do not have permission to access the file '" + location + "'", 
						ExceptionType.SecurityException, t);
			}
			return new CInt(new File(t.file().getParentFile(), location).length(), t);
		}

		public String getName() {
			return "file_size";
		}

		public Integer[] numArgs() {
			return new Integer[]{1};
		}

		public String docs() {
			return "Returns the size of a file on the file system.";
		}
		
		public Argument returnType() {
			return new Argument("The size of a file on the file system. Returns 0 if the file is empty, or otherwise doesn't exist.", CInt.class);
		}

		public ArgumentBuilder arguments() {
			return ArgumentBuilder.Build(
						new Argument("The path to the file, relative to the file this code is being run from", CString.class, "filePath")
					);
		}

		public CHVersion since() {
			return CHVersion.V3_3_1;
		}
		
	}
}
