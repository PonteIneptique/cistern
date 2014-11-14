// Copyright 2013 Thomas Müller
// This file is part of MarMoT, which is licensed under GPLv3.

package marmot.morph;

import java.util.Collection;

import marmot.core.ArrayFloatFeatureVector;
import marmot.core.ConcatFloatFeatureVector;
import marmot.core.Feature;
import marmot.core.FeatureVector;
import marmot.core.FloatFeatureVector;
import marmot.core.FloatWeights;
import marmot.core.Model;
import marmot.core.Sequence;
import marmot.core.State;
import marmot.core.Token;
import marmot.core.WeightVector;
import marmot.core.ZeroFloatFeatureVector;
import marmot.util.Encoder;
import marmot.util.StringUtils.Mode;
import marmot.util.SymbolTable;

public class MorphWeightVector implements WeightVector, FloatWeights {
	private static final long serialVersionUID = 1L;
	private static final int MAX_AFFIX_LENGTH_ = 10;
	private static final int NUM_STATE_FEATURES_ = 3 + 2 + 3 + 1
			+ MAX_AFFIX_LENGTH_ * 2 + 1 + 3;

	private static final int ENCODER_CAPACITY_ = 10;

	private transient Encoder encoder_;
	private transient double[] accumulated_penalty_;
	private boolean penalize_ = false;
	private double linear_penalty_;

	private double[] weights_;
	private double[] float_weights_;

	private boolean extend_feature_set_;
	private MorphModel model_;
	private SymbolTable<Feature> feature_table_;

	private int simple_sub_morph_start_index_;

	private int word_bits_;
	private int[] tag_bits_;
	private int state_feature_bits_;
	private int char_bits_;
	private int shape_bits_;
	private int order_bits_;
	private int[] num_tags_;
	private int total_num_tags_;
	private int level_bits_;
	private int max_level_;
	private double scale_factor_;
	private boolean shape_;
	private int initial_vector_size_;
	private int token_feature_bits_;
	private int max_transition_feature_level_;
	private MorphDictionary mdict_;
	private FloatHashDictionary fdict_;
	private int mdict_bits_;

	// private double[] accumulated_float_penalty_;
	private static final boolean use_bigrams_ = true;
	private static final boolean use_state_features_ = true;

	public MorphWeightVector(MorphOptions options) {
		shape_ = options.getShape();
		max_transition_feature_level_ = options.getMaxTransitionFeatureLevel();
		initial_vector_size_ = options.getInitialVectorSize();

		if (!options.getMorphDict().isEmpty()) {
			mdict_ = MorphDictionary.create(options.getMorphDict());
			mdict_bits_ = Encoder.bitsNeeded(mdict_.numTags());
		}

		if (!options.getFloatTypeDict().isEmpty()) {

			fdict_ = new FloatHashDictionary();

			MorphDictionaryOptions opt = MorphDictionaryOptions.parse(
					options.getFloatTypeDict(), false);
			
			if (opt.getNormalize() == Mode.none) {
				opt.setNormalize(options.getNormalizeForms());
			}

			if (opt.getIndexes() == null) {
				int[] indexes = { 0 };
				opt.setIndexes(indexes);
			}

			fdict_.init(opt);

		}
	}

	@Override
	public void setExtendFeatureSet(boolean flag) {
		extend_feature_set_ = flag;
	}

	@Override
	public void setPenalty(boolean penalize, double linear_penalty) {
		if (!penalize) {
			penalize_ = false;
			accumulated_penalty_ = null;
			linear_penalty_ = 0.0;
		} else {
			penalize_ = true;
			linear_penalty_ = (double) (linear_penalty / scale_factor_);
			if (accumulated_penalty_ == null) {
				accumulated_penalty_ = new double[weights_.length];
			}
			// if (accumulated_float_penalty_ == null && float_weights_ != null)
			// {
			// accumulated_float_penalty_ = new double[float_weights_.length];
			// }

		}
	}

	@Override
	public FeatureVector extractStateFeatures(State state) {
		prepareEncoder();
		MorphFeatureVector new_vector = new MorphFeatureVector(
				1 + state.getLevel(), state.getVector());

		int fc = 0;

		encoder_.append(0, order_bits_);
		encoder_.append(state.getLevel() + 1, level_bits_);
		encoder_.append(fc, 2);

		State run = state.getZeroOrderState();
		while (run != null) {
			encoder_.append(run.getLevel(), level_bits_);
			encoder_.append(run.getIndex(), tag_bits_[run.getLevel()]);
			new_vector.add(getFeatureIndex(encoder_
					.getFeature(extend_feature_set_)));
			run = run.getSubLevelState();
		}
		encoder_.reset();
		fc++;

		new_vector.setIsState(true);
		new_vector.setWordIndex(((MorphFeatureVector) state.getVector())
				.getWordIndex());
		return new_vector;
	}

	@Override
	public FeatureVector extractStateFeatures(Sequence sentence, int token_index) {
		prepareEncoder();
		Token word = sentence.get(token_index);
		Word token = (Word) word;

		int[] mdict_indexes = null;
		if (mdict_ != null) {
			mdict_indexes = mdict_.getIndexes(token.getWordForm());
		}

		short[] chars = token.getCharIndexes();
		assert chars != null;
		int form_index = token.getWordFormIndex();
		boolean is_rare = model_.isRare(form_index);
		MorphFeatureVector features = new MorphFeatureVector(
				NUM_STATE_FEATURES_
						+ 2
						+ ((token.getTokenFeatureIndexes() == null) ? 0
								: token.getTokenFeatureIndexes().length)
						+ ((mdict_indexes == null) ? 0 : mdict_indexes.length));
		int fc = 0;

		if (use_state_features_) {
			if (form_index >= 0) {
				encoder_.append(0, order_bits_);
				encoder_.append(0, level_bits_);
				encoder_.append(fc, state_feature_bits_);
				encoder_.append(form_index, word_bits_);
				features.add(getFeatureIndex(encoder_
						.getFeature(extend_feature_set_)));
				encoder_.reset();

			}
			fc++;

			encoder_.append(0, order_bits_);
			encoder_.append(0, level_bits_);
			encoder_.append(fc, state_feature_bits_);
			encoder_.append(is_rare);
			features.add(getFeatureIndex(encoder_
					.getFeature(extend_feature_set_)));
			encoder_.reset();

			fc++;

			if (token_index - 1 >= 0) {
				int pform_index = ((Word) sentence.get(token_index - 1))
						.getWordFormIndex();
				if (pform_index >= 0) {
					encoder_.append(0, order_bits_);
					encoder_.append(0, level_bits_);
					encoder_.append(fc, state_feature_bits_);
					encoder_.append(pform_index, word_bits_);
					features.add(getFeatureIndex(encoder_
							.getFeature(extend_feature_set_)));

					if (form_index >= 0 && use_bigrams_) {
						encoder_.append(form_index, word_bits_);
						features.add(getFeatureIndex(encoder_
								.getFeature(extend_feature_set_)));
					}
					encoder_.reset();
				}

				int pshape_index = -1;

				if (shape_) {
					pshape_index = ((Word) sentence.get(token_index - 1))
							.getWordShapeIndex();
				}

				if (pshape_index >= 0) {
					encoder_.append(0, order_bits_);
					encoder_.append(0, level_bits_);
					encoder_.append(fc + 1, state_feature_bits_);
					encoder_.append(pshape_index, shape_bits_);

					if (model_.isRare(pform_index)) {
						features.add(getFeatureIndex(encoder_
								.getFeature(extend_feature_set_)));
					}
					encoder_.reset();
				}
			}
			fc++;
			fc++;

			if (token_index + 1 < sentence.size()) {
				int nform_index = ((Word) sentence.get(token_index + 1))
						.getWordFormIndex();

				if (nform_index >= 0) {
					encoder_.append(0, order_bits_);
					encoder_.append(0, level_bits_);
					encoder_.append(fc, state_feature_bits_);
					encoder_.append(nform_index, word_bits_);
					features.add(getFeatureIndex(encoder_
							.getFeature(extend_feature_set_)));

					if (form_index >= 0 && use_bigrams_) {
						encoder_.append(form_index, word_bits_);
						features.add(getFeatureIndex(encoder_
								.getFeature(extend_feature_set_)));
					}
					encoder_.reset();
				}

				int nshape_index = -1;
				if (shape_) {
					nshape_index = ((Word) sentence.get(token_index + 1))
							.getWordShapeIndex();
				}
				if (nshape_index >= 0) {
					encoder_.append(0, order_bits_);
					encoder_.append(0, level_bits_);
					encoder_.append(fc + 1, state_feature_bits_);
					encoder_.append(nshape_index, shape_bits_);

					if (model_.isRare(nform_index)) {
						features.add(getFeatureIndex(encoder_
								.getFeature(extend_feature_set_)));
					}

					encoder_.reset();
				}
			}
			fc++;
			fc++;

			int shape_index = -1;
			if (shape_) {
				shape_index = token.getWordShapeIndex();
			}

			if (is_rare && shape_index >= 0) {
				encoder_.append(0, order_bits_);
				encoder_.append(0, level_bits_);
				encoder_.append(fc, state_feature_bits_);
				encoder_.append(shape_index, shape_bits_);
				features.add(getFeatureIndex(encoder_
						.getFeature(extend_feature_set_)));
				encoder_.reset();
			}
			fc++;

			if (is_rare) {
				int signature = token.getWordSignature();
				encoder_.append(0, order_bits_);
				encoder_.append(0, level_bits_);
				encoder_.append(fc, state_feature_bits_);
				encoder_.append(signature, 4);
				features.add(getFeatureIndex(encoder_
						.getFeature(extend_feature_set_)));
				encoder_.reset();
			}
			fc++;

			// Prefix feature
			if (is_rare) {
				encoder_.append(0, order_bits_);
				encoder_.append(0, level_bits_);
				encoder_.append(fc, state_feature_bits_);

				for (int position = 0; position < Math.min(chars.length,
						MAX_AFFIX_LENGTH_); position++) {
					assert chars != null;
					short c = chars[position];
					if (c < 0) {
						// Unknown character!
						break;
					}
					encoder_.append(c, char_bits_);
					features.add(getFeatureIndex(encoder_
							.getFeature(extend_feature_set_)));
				}
				encoder_.reset();
			}
			fc++;

			// Suffix feature
			if (is_rare) {
				encoder_.append(0, order_bits_);
				encoder_.append(0, level_bits_);
				encoder_.append(fc, state_feature_bits_);
				for (int position = 0; position < Math.min(chars.length,
						MAX_AFFIX_LENGTH_); position++) {
					short c = chars[chars.length - position - 1];
					if (c < 0) {
						// Unknown character!
						break;
					}
					encoder_.append(c, char_bits_);
					features.add(getFeatureIndex(encoder_
							.getFeature(extend_feature_set_)));

				}
				encoder_.reset();
			}
			fc++;
		}

		int[] token_feature_indexes = token.getTokenFeatureIndexes();
		if (token_feature_indexes != null) {
			
			for (int token_feature_index : token_feature_indexes) {
				if (token_feature_index >= 0) {
					encoder_.append(0, order_bits_);
					encoder_.append(0, level_bits_);
					encoder_.append(fc, state_feature_bits_);

					encoder_.append(token_feature_index, token_feature_bits_);
					features.add(getFeatureIndex(encoder_
							.getFeature(extend_feature_set_)));
					encoder_.reset();
				}
			}
			fc++;
		}

		if (fdict_ != null) {

			FloatFeatureVector vector = extractFloatFeatures(sentence, token_index);
			features.setFloatVector(vector);

		} else {
			token_feature_indexes = token.getWeightedTokenFeatureIndexes();
			if (token_feature_indexes != null) {

				features.setFloatVector(new ArrayFloatFeatureVector(
						token_feature_indexes, token
								.getWeightedTokenFeatureWeights(), model_
								.getWeightedTokenFeatureTable().size()));
			}
		}

		if (mdict_indexes != null) {
			for (int index : mdict_indexes) {
				if (index >= 0) {
					encoder_.append(0, order_bits_);
					encoder_.append(0, level_bits_);
					encoder_.append(fc, state_feature_bits_);
					encoder_.append(index, mdict_bits_);
					features.add(getFeatureIndex(encoder_
							.getFeature(extend_feature_set_)));
					encoder_.reset();
				}
			}
		}
		fc++;

		features.setIsState(true);
		features.setWordIndex(form_index);

		return features;
	}

	private FloatFeatureVector extractFloatFeatures(Sequence sentence,
			int token_index) {
		FloatFeatureVector vector = null;

		for (int offset : fdict_.getOffsets()) {
			int index = token_index + offset;

			FloatFeatureVector current_vector = null;
			if (index >= 0 && index < sentence.size()) {
				String form = ((Word) sentence.get(index)).getWordForm();
				current_vector = fdict_.getVector(form);
			}
				
			if (current_vector == null) {
				current_vector = new ZeroFloatFeatureVector(fdict_.size());
			}

			assert (current_vector != null);

			if (vector == null) {
				vector = current_vector;
			} else {
				vector = new ConcatFloatFeatureVector(vector,
						current_vector);
			}
		}

		assert vector != null;
		return vector;
	}

	private int getFeatureIndex(Feature feature) {
		int index = feature_table_.toIndex(feature, -1, extend_feature_set_);
		return index;
	}

	@Override
	public FeatureVector extractTransitionFeatures(State state) {
		prepareEncoder();

		int max_level = state.getLevel();
		int order = state.getOrder();
		FeatureVector features = new FeatureVector(max_level + 1
				+ model_.getNumSubTags());
		for (int depth = 0; depth <= max_level; depth++) {
			int level = max_level - depth;

			if (max_transition_feature_level_ >= 0
					&& level > max_transition_feature_level_) {
				continue;
			}

			encoder_.append(order, order_bits_);
			encoder_.append(level, level_bits_);
			encoder_.append(0, 1);
			State run = state;
			while (run != null) {

				State sub_state = run.getSubLevel(depth);
				int index = sub_state.getIndex();

				encoder_.append(index, tag_bits_[level]);
				run = run.getPreviousSubOrderState();
			}
			features.add(getFeatureIndex(encoder_
					.getFeature(extend_feature_set_)));
			encoder_.reset();

		}
		return features;
	}

	protected double getWeight(int index) {
		return weights_[index];
	}

	@Override
	public double dotProduct(State state, FeatureVector vector) {
		assert vector != null;

		State zero_order_state = state.getZeroOrderState();
		int tag_index = getUniversalIndex(zero_order_state);
		double score = 0.0;

		for (int findex = 0; findex < vector.size(); findex++) {
			int feature = vector.get(findex);
			int index = getIndex(feature, tag_index);
			score += getWeight(index);
		}

		FloatFeatureVector float_vector = vector.getFloatVector();
		if (float_vector != null) {
			score += float_vector.getDotProduct(this, tag_index, 0);
		}

		score += dotProductSubTags(zero_order_state, vector);

		if (vector.getIsState()) {
			int index = getObservedIndex((MorphFeatureVector) vector, state);
			if (index >= 0) {
				score += getWeight(index);
			}
		}

		return score * scale_factor_;
	}

	public double getFloatWeight(int index) {
		return float_weights_[index];
	}

	public int getFloatIndex(int feature, int tag_index) {
		return feature * total_num_tags_ + tag_index;
	}

	private double dotProductSubTags(State state, FeatureVector vector) {
		int level = state.getLevel();

		if (level >= model_.getTagToSubTags().length) {
			return 0.0;
		}

		int[][] tag_to_subtag = model_.getTagToSubTags()[level];

		if (tag_to_subtag == null) {
			return 0.0;
		}

		int[] indexes = tag_to_subtag[state.getIndex()];

		if (indexes == null) {
			return 0.0;
		}

		double score = 0.0;
		for (int index : indexes) {
			int simple_index = getSimpleSubMorphIndex(index);

			for (int findex = 0; findex < vector.size(); findex++) {
				int feature = vector.get(findex);
				int f_index = getIndex(feature, simple_index);
				score += getWeight(f_index);
			}

			FloatFeatureVector float_vector = vector.getFloatVector();
			if (float_vector != null) {
				score += float_vector.getDotProduct(this, simple_index, 0);
			}
		}

		return score;
	}

	private int getProductIndex(State state) {

		if (state.getLevel() == 0) {
			return state.getIndex();
		}

		int size = num_tags_[state.getLevel()];
		return getProductIndex(state.getSubLevelState()) * size
				+ state.getIndex();
	}

	private int getUniversalIndex(int tag_index, int level) {
		for (int clevel = 0; clevel < level; clevel++) {
			tag_index += num_tags_[clevel];
		}
		return tag_index;
	}

	private int getUniversalIndex(State zero_order_state) {
		return getUniversalIndex(zero_order_state.getIndex(),
				zero_order_state.getLevel());
	}

	private int getIndex(int feature, int tag_index) {
		int index = feature * total_num_tags_ + tag_index;

		int capacity = weights_.length - 2 * max_level_;
		int h = index;

		h ^= (h >>> 20) ^ (h >>> 12);
		h = h ^ (h >>> 7) ^ (h >>> 4);
		h = h & (capacity - 1);

		assert h >= 0;
		assert h < capacity;
		return h;

	}

	@Override
	public void init(Model model, Collection<Sequence> sequences) {
		int max_level = model.getTagTables().size();
		feature_table_ = new SymbolTable<Feature>();
		model_ = (MorphModel) model;
		max_level_ = max_level;
		num_tags_ = new int[max_level];
		total_num_tags_ = 0;
		tag_bits_ = new int[max_level];
		for (int level = 0; level < Math.min(model.getTagTables().size(),
				max_level); level++) {
			num_tags_[level] = model.getTagTables().get(level).size();
			tag_bits_[level] = Encoder.bitsNeeded(num_tags_[level]);
			total_num_tags_ += num_tags_[level];
		}

		simple_sub_morph_start_index_ = total_num_tags_;
		total_num_tags_ += model_.getNumSubTags();
		// tuple_sub_morph_start_index_ = total_num_tags_;
		// total_num_tags_ += model.getNumSubMorphTags() * num_tags_[0];

		word_bits_ = Encoder.bitsNeeded(model_.getWordTable().size());
		state_feature_bits_ = Encoder.bitsNeeded(NUM_STATE_FEATURES_);
		char_bits_ = Encoder.bitsNeeded(model_.getCharTable().size());
		if (shape_)
			shape_bits_ = Encoder.bitsNeeded(model_.getNumShapes());
		order_bits_ = Encoder.bitsNeeded(model.getOrder());
		level_bits_ = Encoder.bitsNeeded(max_level);

		token_feature_bits_ = Encoder.bitsNeeded(model_.getTokenFeatureTable()
				.size());
		
		if (fdict_ != null) {
			float_weights_ = new double[fdict_.getOffsets().length
					* fdict_.size() * total_num_tags_];
		} else {
			float_weights_ = new double[model_.getWeightedTokenFeatureTable()
					.size() * total_num_tags_];
		}

		extend_feature_set_ = true;
		scale_factor_ = 1.;

		int capacity = 1;
		int initial_size = initial_vector_size_;
		while (capacity < initial_size)
			capacity <<= 1;

		weights_ = new double[capacity + 2 * max_level];
	}

	private void update(State state, double value) {
		FeatureVector vector = state.getVector();
		if (vector != null) {
			State run = state.getZeroOrderState();

			while (run != null) {

				int tag_index = getUniversalIndex(run);
				for (int findex = 0; findex < vector.size(); findex++) {
					int feature = vector.get(findex);
					int index = getIndex(feature, tag_index);
					updateWeight(index, value);
				}

				FloatFeatureVector float_vector = vector.getFloatVector();
				if (float_vector != null) {
					float_vector.updateFloatWeight(this, tag_index, 0, value);
				}

				updateSubTags(run, vector, value);

				if (state.getOrder() == 1) {
					run = null;

					State sub_level_state = state.getSubLevelState();
					if (sub_level_state != null)
						update(sub_level_state, value);

					if (vector.getIsState()) {
						int index = getObservedIndex(
								(MorphFeatureVector) vector, state);
						if (index >= 0) {
							updateWeight(index, value);
						}
					}

				} else {
					run = run.getSubLevelState();
				}
			}
		}
	}

	private void updateSubTags(State state, FeatureVector vector, double value) {
		int level = state.getLevel();

		if (level >= model_.getTagToSubTags().length) {
			return;
		}

		int[][] tag_to_subtag = model_.getTagToSubTags()[level];

		if (tag_to_subtag == null) {
			return;
		}

		int[] indexes = tag_to_subtag[state.getIndex()];

		if (indexes == null) {
			return;
		}

		for (int index : indexes) {
			int simple_index = getSimpleSubMorphIndex(index);
			for (int findex = 0; findex < vector.size(); findex++) {
				int feature = vector.get(findex);
				int f_index = getIndex(feature, simple_index);
				updateWeight(f_index, value);
			}

			FloatFeatureVector float_vector = vector.getFloatVector();
			if (float_vector != null) {
				float_vector.updateFloatWeight(this, simple_index, 0, value);				
			}
		}

	}

	protected int getSimpleSubMorphIndex(int sub_morph_index) {
		return simple_sub_morph_start_index_ + sub_morph_index;
	}

	protected void updateWeight(int index, double value) {
		weights_[index] += value;
		if (penalize_) {
			weights_[index] = applyPenalty(index, weights_[index]);
		}
	}

	public void updateFloatWeight(int index, double value) {
		float_weights_[index] += value;
	}

	protected int getObservedIndex(MorphFeatureVector vector, State state) {
		int word_index = vector.getWordIndex();
		int level = state.getLevel();
		int product_index = getProductIndex(state);

		int feature = model_.hasBeenObserved(word_index, level, product_index) ? 0
				: 1;

		int start_index = weights_.length - max_level_ * 2;
		int index = start_index + level * 2 + feature;
		return index;
	}

	protected double applyPenalty(int index, double weight) {
		double z = weight;

		if (z - 1e-10 > 0.) {
			weight = Math.max(0, z
					- (linear_penalty_ + accumulated_penalty_[index]));
		} else if (z + 1e-10 < 0.) {
			weight = Math.min(0, z
					+ (linear_penalty_ - accumulated_penalty_[index]));
		}

		accumulated_penalty_[index] += weight - z;
		return weight;
	}

	protected void prepareEncoder() {
		if (encoder_ == null) {
			encoder_ = new Encoder(ENCODER_CAPACITY_);
		}
		encoder_.reset();
	}

	@Override
	public void updateWeights(State state, double value, boolean is_transition) {
		value /= scale_factor_;

		update(state, value);
		if (!is_transition) {
			while ((state = state.getSubOrderState()) != null) {
				update(state, value);
			}
		}
	}

	@Override
	public void scaleBy(double scale_factor) {
		linear_penalty_ /= scale_factor;
		scale_factor_ *= scale_factor;
	}

	@Override
	public double[] getWeights() {
		return weights_;
	}

	@Override
	public void setWeights(double[] weights) {
		weights_ = weights;
	}

	public MorphDictionary getMorphDict() {
		return mdict_;
	}

	public SymbolTable<Feature> getFeatureTable() {
		return feature_table_;
	}
}
