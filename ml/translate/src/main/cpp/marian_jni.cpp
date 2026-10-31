// JNI bridge to Marian NMT (ADR 0007): loads one translation direction (a Firefox Translations
// model, its SentencePiece vocabulary and its lexical shortlist) and translates lines of text.

#include <jni.h>

#include <android/log.h>

#include <unistd.h>

#include <memory>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "common/config.h"
#include "common/logging.h"
#include "common/utils.h"
#include "translator/beam_search.h"
#include "translator/translator.h"

namespace {

constexpr const char* kTag = "MarianJni";

using namespace marian;

// One direction on one CPU thread, as marian-decoder sets it up (Translate in
// translator/translator.h, MIT): unlike TranslateService, the graph runs once after the model
// is loaded, which prepares its int8 weights. Batches run on the calling thread.
class Translator {
 public:
  explicit Translator(Ptr<Options> options) : options_(New<Options>(options->clone())) {
    options_->set("inference", true, "shuffle", "none");
    auto vocabPaths = options_->get<std::vector<std::string>>("vocabs");
    auto maxVocabs = options_->get<std::vector<int>>("dim-vocabs");
    for (size_t i = 0; i + 1 < vocabPaths.size(); ++i) {
      auto vocab = New<Vocab>(options_, i);
      vocab->load(vocabPaths[i], maxVocabs[i]);
      srcVocabs_.push_back(vocab);
    }
    trgVocab_ = New<Vocab>(options_, vocabPaths.size() - 1);
    trgVocab_->load(vocabPaths.back());
    if (options_->hasAndNotEmpty("shortlist")) {
      auto file = options_->get<std::vector<std::string>>("shortlist")[0];
      bool shared = vocabPaths.front() == vocabPaths.back();
      if (data::isBinaryShortlist(file)) {
        shortlist_ = New<data::BinaryShortlistGenerator>(options_, srcVocabs_.front(), trgVocab_,
                                                         0, 1, shared);
      } else {
        shortlist_ = New<data::LexicalShortlistGenerator>(options_, srcVocabs_.front(),
                                                          trgVocab_, 0, 1, shared);
      }
    }
    std::vector<std::vector<io::Item>> items;
    for (auto& model : options_->get<std::vector<std::string>>("models")) {
      items.push_back(io::loadItems(model));
    }
    graph_ = New<ExpressionGraph>(true);
    auto precision = options_->get<std::vector<std::string>>("precision", {"float32"});
    graph_->setDefaultElementType(typeFromString(precision.empty() ? "float32" : precision[0]));
    graph_->setDevice(Config::getDevices(options_)[0]);
    graph_->getBackend()->configureDevice(options_);
    graph_->reserveWorkspaceMB(options_->get<size_t>("workspace"));
    scorers_ = createScorers(options_, items);
    for (auto& scorer : scorers_) {
      scorer->init(graph_);
      if (shortlist_) scorer->setShortlistGenerator(shortlist_);
    }
    graph_->forward();
  }

  std::string run(const std::string& input) {
    auto corpus = New<data::TextInput>(std::vector<std::string>({input}), srcVocabs_, options_);
    data::BatchGenerator<data::TextInput> batches(corpus, options_, nullptr, false);
    auto collector = New<StringCollector>(true);
    auto printer = New<OutputPrinter>(options_, trgVocab_);
    batches.prepare();
    for (auto batch : batches) {
      auto search = New<BeamSearch>(options_, scorers_, trgVocab_);
      for (auto history : search->search(graph_, batch)) {
        std::stringstream best1;
        std::stringstream bestn;
        printer->print(history, best1, bestn);
        collector->add(static_cast<long>(history->getLineNum()), best1.str(), bestn.str());
      }
    }
    return utils::join(collector->collect(options_->get<bool>("n-best")), "\n");
  }

 private:
  Ptr<Options> options_;
  Ptr<ExpressionGraph> graph_;
  std::vector<Ptr<Scorer>> scorers_;
  std::vector<Ptr<Vocab>> srcVocabs_;
  Ptr<Vocab> trgVocab_;
  Ptr<const data::ShortlistGenerator> shortlist_;
};

std::string toString(JNIEnv* env, jstring value) {
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

void throwJava(JNIEnv* env, const char* message) {
  jclass type = env->FindClass("java/io/IOException");
  env->ThrowNew(type, message);
}

// Marian reports its errors on stderr, which Android drops: they go to logcat instead.
void logStderr() {
  int fds[2];
  if (pipe(fds) != 0) return;
  dup2(fds[1], STDERR_FILENO);
  close(fds[1]);
  std::thread([fd = fds[0]] {
    char buffer[1024];
    ssize_t n;
    while ((n = read(fd, buffer, sizeof(buffer) - 1)) > 0) {
      buffer[n] = 0;
      __android_log_write(ANDROID_LOG_WARN, kTag, buffer);
    }
  }).detach();
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
  logStderr();
  // Marian aborts the process on an error unless told to throw; a bad model file must not close
  // the app. (Its option parser still exits on an unknown option, so the options are fixed.)
  marian::setThrowExceptionOnAbort(true);
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_tech_idct_clipra_ml_translate_Marian_open(JNIEnv* env, jclass, jstring model, jstring vocab,
                                               jstring shortlist, jint threads) {
  // The decoding settings Firefox uses for these models: greedy search, int8 products with
  // the model's own scaling, and the lexical shortlist.
  std::vector<std::string> args = {
      "marian",
      "--models", toString(env, model),
      "--vocabs", toString(env, vocab), toString(env, vocab),
      "--beam-size", "1",
      "--normalize", "1.0",
      "--word-penalty", "0",
      "--max-length-factor", "2.0",
      "--mini-batch-words", "1024",
      "--workspace", "128",
      "--cpu-threads", std::to_string(threads),
      "--gemm-precision", "int8shiftAlphaAll",
      "--skip-cost",
      "--quiet",
      "--quiet-translation",
  };
  std::string shortlistPath = toString(env, shortlist);
  if (!shortlistPath.empty()) {
    args.insert(args.end(), {"--shortlist", shortlistPath, "false"});
  }
  std::vector<char*> argv;
  for (auto& arg : args) argv.push_back(&arg[0]);
  try {
    // Marian registers its loggers each time it reads options, and spdlog refuses a name it
    // already has: drop them, or the second model loaded (any pair through English) fails.
    spdlog::drop("general");
    spdlog::drop("valid");
    auto options = marian::parseOptions(static_cast<int>(argv.size()), argv.data(),
                                        marian::cli::mode::translation, true);
    return reinterpret_cast<jlong>(new Translator(options));
  } catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "Cannot load the model: %s", e.what());
    throwJava(env, e.what());
    return 0;
  }
}

// Text goes both ways as UTF-8 bytes: JNI's own strings are modified UTF-8, which would garble
// characters outside the Basic Multilingual Plane, such as emoji in captions.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_tech_idct_clipra_ml_translate_Marian_translate(JNIEnv* env, jclass, jlong handle,
                                                    jbyteArray text) {
  auto* translator = reinterpret_cast<Translator*>(handle);
  try {
    jsize length = env->GetArrayLength(text);
    std::string input(static_cast<size_t>(length), ' ');
    env->GetByteArrayRegion(text, 0, length, reinterpret_cast<jbyte*>(&input[0]));
    std::string result = translator->run(input);
    jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size()));
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()),
                            reinterpret_cast<const jbyte*>(result.data()));
    return output;
  } catch (const std::exception& e) {
    throwJava(env, e.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_tech_idct_clipra_ml_translate_Marian_close(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<Translator*>(handle);
}
