package org.scalaide.worksheet.editor

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.tools.eclipse.ISourceViewerEditor
import scala.tools.eclipse.logging.HasLogger
import org.eclipse.jface.action.IMenuManager
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.source.Annotation
import org.eclipse.jface.text.source.IAnnotationModel
import org.eclipse.jface.text.source.IAnnotationModelExtension2
import org.eclipse.jface.text.source.ISourceViewer
import org.eclipse.jface.util.IPropertyChangeListener
import org.eclipse.jface.util.PropertyChangeEvent
import org.eclipse.swt.events.KeyAdapter
import org.eclipse.swt.events.KeyEvent
import org.eclipse.ui.editors.text.TextEditor
import org.eclipse.ui.texteditor.ITextEditorActionConstants
import org.eclipse.ui.texteditor.TextOperationAction
import org.scalaide.worksheet.ScriptCompilationUnit
import org.scalaide.worksheet.WorksheetPlugin
import org.scalaide.worksheet.editor.action.RunEvaluationAction
import org.eclipse.jface.text.IDocument

object ScriptEditor {

  /** The annotation types showin when hovering on the left-side ruler (or in the status bar). */
  private[editor] val annotationsShownInHover = Set(
    "org.eclipse.jdt.ui.error", "org.eclipse.jdt.ui.warning", "org.eclipse.jdt.ui.info")

  val SCRIPT_EXTENSION = ".sc"

  val TAB_WIDTH = 2
}

/** A Scala script editor.*/
class ScriptEditor extends TextEditor with SelectionTracker with ISourceViewerEditor with HasLogger {
  @inline private def prefStore = WorksheetPlugin.prefStore

  private lazy val sourceViewConfiguration = new ScriptConfiguration(prefStore, this)
  setSourceViewerConfiguration(sourceViewConfiguration)
  setPreferenceStore(prefStore)
  setPartName("Scala Script Editor")
  setDocumentProvider(new ScriptDocumentProvider)

  private class StopEvaluationOnKeyPressed(editorProxy: DefaultEditorProxy) extends KeyAdapter {
    override def keyPressed(e: KeyEvent) {
      // Don't stop evaluation if no real character is entered in the editor (e.g., KEY UP/DOWN)
      if (Character.isLetterOrDigit(e.character) || Character.isSpace(e.character))
        stopEvaluation()
    }
  }

  /** This class is used by the `IncrementalDocumentMixer` to update the editor's document with
   *  the evaluation's result.
   */
  private class DefaultEditorProxy extends EditorProxy {
    import scala.tools.eclipse.util.SWTUtils

    @volatile private[ScriptEditor] var ignoreDocumentUpdate = false
    private val stopEvaluationListener = new StopEvaluationOnKeyPressed(this)

    @inline private def doc: IDocument = getDocumentProvider.getDocument(getEditorInput)

    override def getContent: String = doc.get

    override def replaceWith(content: String, newCaretOffset: Int): Unit = {
      if (!ignoreDocumentUpdate) runInUi {
        doc.set(content)
        // we need to turn off evaluation on save if we don't want to loop forever 
        // (because of `editorSaved` implementation, i.e., automatic worksheet evaluation on save)
        getSourceViewer().getTextWidget().setCaretOffset(newCaretOffset)
        getSourceViewer().invalidateTextPresentation()
      }
    }

    override def caretOffset: Int = {
      var offset = -1
      runInUi { //FIXME: Can I make this non-blocking!?
        offset = getSourceViewer().getTextWidget().getCaretOffset()
      }
      offset
    }

    override def completedExternalEditorUpdate(): Unit = {
      stopExternalEditorUpdate()
      save()
    }

    private def save(): Unit = runInUi {
      evaluationOnSave = false
      doSave(null)
      evaluationOnSave = true
    }

    private[ScriptEditor] def prepareExternalEditorUpdate(): Unit = {
      ignoreDocumentUpdate = false
      runInUi {
        getSourceViewer().getTextWidget().addKeyListener(stopEvaluationListener)
      }
    }

    private[ScriptEditor] def stopExternalEditorUpdate(): Unit = {
      ignoreDocumentUpdate = true
      runInUi {
        getSourceViewer().getTextWidget().removeKeyListener(stopEvaluationListener)
      }
    }

    private def runInUi(f: => Unit): Unit = SWTUtils.asyncExec {
      /* We need to make sure that the editor was not `disposed` before executing `f` 
       * in the UI thread. The reason is that it is possible that after calling 
       * `SWTUtils.asyncExec`, but before the passed closure is executed, the editor 
       * may be disposed. If the editor is disposed, executing `f` will likely end up 
       * throwing a NPE.
       * 
       * FIXME: 
       * Having said all the above, there is still a possible race condition, i.e., what 
       * if `disposed` becomes true while `f` is being executed (since the two actions can 
       * happen in parallel on two different threads). Well, this is indeed an issue, which 
       * I fear need some thinking to be effectively fixed.
       */
      if (!disposed) f
    }
  }

  @volatile private var evaluationOnSave = true
  @volatile private var disposed = false

  private val editorProxy = new DefaultEditorProxy

  override def handlePreferenceStoreChanged(event: PropertyChangeEvent) = {
    sourceViewConfiguration.handlePropertyChangeEvent(event)
    getSourceViewer().invalidateTextPresentation()
  }

  override def initializeKeyBindingScopes() {
    setKeyBindingScopes(Array("org.scalaide.worksheet.editorScope"))
  }

  override def createActions() {
    super.createActions()

    // Adding source formatting action in the editor popup dialog 
    val formatAction = new TextOperationAction(EditorMessages.resourceBundle, "Editor.Format.", this, ISourceViewer.FORMAT)
    formatAction.setActionDefinitionId("org.scalaide.worksheet.commands.format")
    setAction("format", formatAction)

    // Adding run evaluation action in the editor popup dialog
    val evalScriptAction = new RunEvaluationAction(this)
    evalScriptAction.setActionDefinitionId("org.scalaide.worksheet.commands.evalScript")
    setAction("evalScript", evalScriptAction)
  }

  override def editorContextMenuAboutToShow(menu: IMenuManager) {
    super.editorContextMenuAboutToShow(menu)

    // add the format menu itemevalScript    
    addAction(menu, ITextEditorActionConstants.GROUP_EDIT, "evalScript")
    addAction(menu, ITextEditorActionConstants.GROUP_EDIT, "format")
  }

  /** Return the annotation model associated with the current document. */
  private def annotationModel: IAnnotationModel with IAnnotationModelExtension2 = getDocumentProvider.getAnnotationModel(getEditorInput).asInstanceOf[IAnnotationModel with IAnnotationModelExtension2]

  def selectionChanged(selection: ITextSelection) {
    val iterator = annotationModel.getAnnotationIterator(selection.getOffset, selection.getLength, true, true).asInstanceOf[java.util.Iterator[Annotation]]
    val msg = iterator.asScala.find(a => ScriptEditor.annotationsShownInHover(a.getType)).map(_.getText).getOrElse(null)
    setStatusLineErrorMessage(msg)
  }

  def getViewer: ISourceViewer = getSourceViewer

  override protected def editorSaved(): Unit = {
    super.editorSaved()
    //FIXME: Should be configurable
    if (evaluationOnSave) runEvaluation()
  }

  override def dispose(): Unit = {
    disposed = true
    stopEvaluation()
    super.dispose()
  }

  private[worksheet] def runEvaluation(): Unit = withScriptCompilationUnit {
    editorProxy.prepareExternalEditorUpdate()

    import org.scalaide.worksheet.runtime.WorksheetsManager
    import org.scalaide.worksheet.runtime.WorksheetRunner
    WorksheetsManager.Instance ! WorksheetRunner.RunEvaluation(_, editorProxy)
  }

  private[worksheet] def stopEvaluation(): Unit = withScriptCompilationUnit {
    editorProxy.stopExternalEditorUpdate()

    import org.scalaide.worksheet.runtime.WorksheetsManager
    import org.scalaide.worksheet.runtime.ProgramExecutorService
    WorksheetsManager.Instance ! ProgramExecutorService.StopRun(_)
  }

  private def withScriptCompilationUnit(f: ScriptCompilationUnit => Unit): Unit = {
    ScriptCompilationUnit.fromEditor(ScriptEditor.this) foreach f
  }
}
