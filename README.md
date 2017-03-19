# vsts-work-migrate

Tool to migrate work items from vsts.

## Prerequisites

* [Java 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html) (check with `java -version`)

* MSEng personal access token: Go to [mseng > you > security](https://mseng.visualstudio.com/_details/security/tokens) - create a token with `Work items (read)` scope. 
* Set environment variable `MSENG_ACCESS_TOKEN` to the token created above.

* MSMobilecenter personal access token: Go to [msmobile > you > security](https://msmobilecenter.visualstudio.com/_details/security/tokens) - create token with `Work items (read and write)`, `Project and team (read)` scope. 
* Set environment variable `MOBILECENTER_ACCESS_TOKEN` to the token created.


Note on windows you have to run this as 
```
java -jar target/vsts-migrate.jar
```

on Mac/Linux you can run `./vsts-migrate` as a wrapper for `java -jar`.

## Guide 

The `vsts-migrate` tool can do three things.

1. dump specific work items from the [mseng](https://mseng.visualstudio.com/Mobile%20Center/) VSTS instance 

2. copy a dump into the [msmobilecenter](https://msmobilecenter.visualstudio.com/) instance

3. undo the copy by deleting created items from msmobilecenter

All commands support a `-d`/`--dry-run` option which only performs queries it does not create or delete items. The tool also support a `-h`/`--help` option (`./vsts-migrate -h`).

Below are the details which you should read first.

### Dump

The `dump-from-mseng` command allows you to specify a "query" to be evaluated in the MSeng instance. The query evaluates to a number of work items which satisfy the query. After the query is performed, each work item is mapped using a mapping specification (e.g., `mappings.json`), and the resulting work items are saved in json format for inspection. (If you're happy with the result, the idea is that you use the `copy-to-msmobilecenter` defined below to import into the msmobilecenter instance). 

*Below we show how mappings and queries* work. Note, you must customize both a `mappings.json` and `query-file` to use this tool.

Here is the default `mappings.json` file:

```json
{
 "System.State": {"New": "New",
                  "Proposed":"New",
                  "Committed":"New",
                  "On Deck":"New",
                  "In Progress":"Active",
                  "Active":"Active"},
 "System.WorkItemType": {"Scenario":"Epic",
                         "Feature":"Feature",
                         "User Story":"User Story",
                         "Task":"Task",
                         "Bug":"Bug"},
 "System.AreaPath": {
     "Mobile Center\\Test Cloud": "Test",
     "Mobile Center\\Test Cloud\\Web": "Test\\Web",
     "Mobile Center\\Test Cloud\\Services and Frameworks": "Test\\Services"
 }
}
```

For example, an MSEng workitem with `System.State` of `"On Deck"` would have a `System.State` mapped to `"New"` in the msmobilcenter instance.

The `query-file` is a text file with a "WIQL" query (Work Item Query Language) query. For example, here is the one for Test Cloud:

```
SELECT
        [System.Id],
        [System.Rev],
        [System.WorkItemType],
        [System.Title],
        [System.State],
        [System.AreaPath],
        [System.IterationPath],
        [System.Description],
        [System.AssignedTo],
        [System.Tags]
FROM workitems
WHERE
        [System.TeamProject] = @project
        AND [System.AreaPath] UNDER "Mobile Center\Test Cloud"
        AND [System.State] IN ("New", "Proposed","On Deck","Committed","In Progress","Active")
        AND [System.Tags] NOT CONTAINS "NO_MIGRATE"
ORDER BY [System.ChangedDate] DESC

```

You can adapt the example above (also found in the [test-cloud.wiql](test-cloud.wiql) file), or you can learn about the WIQL language and interactively build a query string in the mseng instance via:

https://mseng.visualstudio.com/Mobile%20Center/_apps/hub/ottostreifel.wiql-editor.wiql-playground-hub 


Example of usage: 

```bash
# dump-from-mseng - Dump (and pretty print) WIQL query results from MSEng instance. 
# Args: <wiql-file-path> <mappings.json> <dump-file-path

vsts-work-migrate git:(master) ✗ ./vsts-migrate dump-from-mseng test-cloud.wiql mappings.json mseng-test-dump.json
Dumping test-cloud.wiql from mseng via mapping: mappings.json
Options {}
SELECT
        [System.Id],
...
Feature (878092): Admin Pages For Support and Sales
    User Story (878093): Add Users page to sales admin
Feature (876423): E2E customer workflow
    User Story (876625): As a User, I want to download all screenshots from a selected device so that I can review them offline.
...
Saved output in /Users/krukow/code/vsts-work-migrate/mseng-test-dump.json
```

### Copy

The `copy-to-msmobilecenter` copies a dump json file into the msmobilecenter instance. To use `copy-to-msmobilecenter`, you must first create a dump via the instructions on `dump-from-mseng` above. 

*Note* You must pick a specific *project* in the msmobilecenter instance which you have access to, and that you want to import the work items into. In the example below, I use the [Test](https://msmobilecenter.visualstudio.com/Test) project.

```
# copy-to-msmobilecenter - Copy query result to msmobilecenter. 
# Args: <dump-file-path> <Project> <saved-results-output-path>

vsts-work-migrate git:(master) ✗ ./vsts-migrate copy-to-msmobilecenter mseng-test-dump.json Test msmobilecenter-created.json
...
Saving created items as mapping from mseng items to msmobilcenter items as /Users/krukow/code/vsts-work-migrate/msmobilecenter-created.json
```
This will copy the work items from the `mseng-test-dump.json` file into the `Test` project in msmobilecenter and save the result as `msmobilecenter-created.json`. 

*CAVEATS*: 
* We preserve the following fields only (`:path`s below)
* The default is to query only "new" and "active" work items 
* We preserve only parent/child links between work items.
* The history/comments/discussion field seems to not be fully preserved
* Note, specifically that, IterationPaths are not preserved. 

```clojure
            [{:op "add"
              :path "/fields/System.Title"
              :value (get-item-field work-item :Title)}
             {:op "add"
              :path "/fields/System.AreaPath"
              :value (get-item-field work-item :AreaPath)}
             {:op "add"
              :path "/fields/System.State"
              :value (get-item-field work-item :State)}
             {:op "add"
              :path "/fields/System.Description"
              :value (get-item-field work-item :Description)}
             {:op "add"
              :path "/fields/System.AssignedTo"
              :value (get-item-field work-item :AssignedTo)}
             {:op "add"
              :path "/fields/Microsoft.VSTS.Common.Priority"
              :value (get-in work-item [:fields "Microsoft.VSTS.Common.Priority"])}
             {:op "add"
              :path "/fields/System.History"
              :value (get-item-field work-item :History)}
             {:op "add"
              :path "/fields/System.CreatedBy"
              :value (get-item-field work-item :CreatedBy)}
             {:op "add"
              :path "/fields/System.Tags"
              :value (get-item-field work-item :Tags)}]
```

## Delete 

Finally, if you're unhappy with the work items you created you can delete them again from msmobilecenter by running

```
# delete-from-msmobilecenter  - Delete previously copied work-items from msmobilecenter (i.e. "undo" creation).
# Args: <saved-results-output-path>.

./vsts-migrate delete-from-msmobilecenter msmobilecenter-created.json
```

where `msmobilecenter-created.json` is the file created in the `Copy` step avove.

# Simple usage / Fast-track

If you don't care too much about the details and just want some thing simple that should work for most cases do this. Make sure you've created personal access tokens and set environment variables as specified in the "Prerequisites".

1. Make a copy of the `test-cloud.wiql`, say to `my-query.wiql`
2. Edit the `my-query.wiql` file to change the line "`AND [System.AreaPath] UNDER "Mobile Center\Test Cloud"`" to specify the beacon area-path you want to migrate to the msmobilecenter  instance. You should *not* select just "Mobile Center" and you should *only* select an area path you fully are responsible for migrating.
3. Edit the `mappings.json` file, specifically the `System.AreaPath` property. This is where you specify how to map area-paths from mseng into desired area-paths in msmobilecenter. Remember to escape back-slash with "\\". Make sure you specify a mapping for *all* the possible area paths you might encounter for your work items in mseng. Note IterationPaths are not preserved. 

Now you're ready for a dry-run.

4. Run `./vsts-migrate dump-from-mseng my-query.wiql mappings.json mseng-dump.json`
5. Sanity check the pretty printed output and the output in `mseng-dump.json` 

If you're happy and ready to pull the triger and import to msmobilecenter. Make sure the msmobilecenter project you want to import to is set up - let's call it "Project" below:

6. `./vsts-migrate copy-to-msmobilecenter mseng-dump.json <Project> msmobilecenter-created.json`

Inspect the msmobilecenter instance to check that everything looks good. If you realize there is a problem, don't panic. You can delete the created work items using:

7. Only run this if things went bad: `./vsts-migrate delete-from-msmobilecenter msmobilecenter-created.json`


## License

Copyright © 2017 Karl Krukow

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
