/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.doc.cypherdoc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.impl.EphemeralFileSystemAbstraction;

/**
 * Parse AsciiDoc-like content for use in Cypher documentation.
 * 
 * <pre>
 * The string/file is parsed top to bottom.
 * The database isn't flushed: every query builds on the state left
 * behind by the previous ones.
 * 
 * Commands:
 *   // console
 *     Adds an empty div with the class cypherdoc-console to HTML outputs. 
 *   // graph: name
 *     Adds a graphviz graph with "name" in the generated filename.
 *     It will depict whatever state the graph is in at that moment.
 *   Extra lines directly after a query:
 *     The query result will be searched for each of the strings (one string per line).
 * </pre>
 */
public final class CypherDoc
{
    static final String EOL = System.getProperty( "line.separator" );

    private CypherDoc()
    {
    }

    /**
     * Parse a string as CypherDoc-enhanced AsciiDoc.
     */
    public static String parse( String input )
    {
        List<Block> blocks = parseBlocks( input );

        EphemeralFileSystemAbstraction fs = new EphemeralFileSystemAbstraction();
        GraphDatabaseService database = new TestGraphDatabaseFactory().setFileSystem( fs ).newImpermanentDatabase();
        TestFailureException failure = null;
        try
        {
            ExecutionEngine engine = new ExecutionEngine( database );
            return executeBlocks( blocks, new State( engine, database ) );
        }
        catch ( TestFailureException exception )
        {
            dumpStoreFiles( fs, failure = exception, "before-shutdown" );
            throw exception;
        }
        finally
        {
            database.shutdown();
            if ( failure != null )
            {
                dumpStoreFiles( fs, failure, "after-shutdown" );
            }
        }
    }

    static List<Block> parseBlocks( String input )
    {
        String[] lines = input.split( EOL );
        if ( lines.length < 3 )
        {
            throw new IllegalArgumentException( "To little content, only "
                                                + lines.length + " lines." );
        }
        List<Block> blocks = new ArrayList<>();
        List<String> currentBlock = new ArrayList<>();
        for ( String line : lines )
        {
            if ( line.trim().isEmpty() )
            {
                if ( !currentBlock.isEmpty() )
                {
                    blocks.add( Block.getBlock( currentBlock ) );
                    currentBlock = new ArrayList<>();
                }
            }
            else if ( line.startsWith( "//" ) && !line.startsWith( "////" ) && currentBlock.isEmpty() )
            {
                blocks.add( Block.getBlock( Collections.singletonList( line ) ) );
            }
            else
            {
                currentBlock.add( line );
            }
        }
        if ( !currentBlock.isEmpty() )
        {
            blocks.add( Block.getBlock( currentBlock ) );
        }
        return blocks;
    }

    private static String executeBlocks( List<Block> blocks, State state )
    {
        StringBuilder output = new StringBuilder( 4096 );
        boolean hasConsole = false;
        for ( Block block : blocks )
        {
            if ( block.type == BlockType.CONSOLE )
            {
                hasConsole = true;
            }
            output.append( block.process( state ) )
                  .append( EOL )
                  .append( EOL );
        }
        if ( !hasConsole )
        {
            output.append( BlockType.CONSOLE.process( null, state ) );
        }

        return output.toString();
    }

    static String indent( String string )
    {
        return string.replace( "\r\n", "\n" ).replace( "\n", EOL + "\t" );
    }

    private static void dumpStoreFiles( EphemeralFileSystemAbstraction fs, TestFailureException exception, String when )
    {
        ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
        try
        {
            fs.dumpZip( snapshot );
            exception.addSnapshot( when + ".zip", snapshot.toByteArray() );
        }
        catch ( IOException e )
        {
            snapshot.reset();
            e.printStackTrace( new PrintStream( snapshot ) );
            exception.addSnapshot( "dump-exception-" + when + ".txt", snapshot.toByteArray() );
        }
    }
}
